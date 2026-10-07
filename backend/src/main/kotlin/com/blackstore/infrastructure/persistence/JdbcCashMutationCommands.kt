package com.blackstore.infrastructure.persistence

import com.blackstore.domain.cash.*
import com.blackstore.domain.identity.*
import com.blackstore.domain.ledger.ExpenseRecord
import com.blackstore.domain.sales.PaymentMethod
import com.blackstore.domain.port.out.cash.CashMutationCommands
import org.postgresql.util.PSQLException
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import javax.sql.DataSource

/** All command writes and authority reads use one physical connection and one transaction. */
@Component
@ConditionalOnProperty(name = ["blackstore.persistence.enabled"], havingValue = "true")
class JdbcCashMutationCommands(private val source: DataSource) : CashMutationCommands {
    private val writer = JdbcBlackStoreWriter()
    private val policy = CashMutationPolicy()
    private val sessions = LoadLockedCashSession()
    private val authority = LoadCashAuthority()

    override fun open(staff: AuthenticatedStaff, terminalId: Long, cashierId: Long, amount: BigDecimal, reason: String?, now: Instant): CashMutationResult<CashSession> = outcome {
        try {
            transaction { connection ->
                role(connection, RuntimeCashRole.Application)
                val currentStaff = authority.actor(connection, staff.id)
                val candidate = CashSession(0, terminalId, cashierId, now, policy.money(amount))
                authorize(currentStaff, StaffPermission.CashSessionOpen, candidate, reason)
                authority.eligibleOwner(connection, StaffUserId(cashierId))
                val id = writer.insertOpenCashSession(connection,terminalId,cashierId,candidate.openingCash,now)
                audit(connection, currentStaff, CashAuditEventType.CASH_SESSION_OPENED, id, reason)
                candidate.copy(id = id)
            }
        } catch (error: PSQLException) {
            // The failed insertion has already rolled back before the visibility lookup starts.
            val constraint = error.serverErrorMessage?.constraint
            if (error.sqlState != "23505" || constraint !in setOf("uq_open_cash_session_per_terminal", "uq_open_cash_session_per_cashier")) throw error
            transaction { connection ->
                role(connection, RuntimeCashRole.Application)
                val actor = authority.actor(connection, staff.id)
                val blocking = sessions.blocking(connection, terminalId, cashierId)
                if (blocking.isEmpty())
                    throw CashMutationException(CashMutationFailure.NotVisible)
                if (blocking.any { policy.authorize(actor,StaffPermission.CashSessionOpen,it,reason) == CashMutationFailure.NotVisible })
                    throw CashMutationException(CashMutationFailure.NotVisible,CashRejectionSource.Authorization)
                blocking.forEach { authorize(actor,StaffPermission.CashSessionOpen,it,reason) }
                throw CashMutationException(CashMutationFailure.Conflict)
            }
        }
    }

    override fun close(staff: AuthenticatedStaff, sessionId: Long, amount: BigDecimal, reason: String?, now: Instant): CashMutationResult<CashSession> = outcome {
        transaction { connection ->
            role(connection, RuntimeCashRole.Projection)
            val session = sessions.lock(connection,sessionId)
            role(connection, RuntimeCashRole.Application)
            val actor = authority.actor(connection,staff.id)
            authorize(actor,StaffPermission.CashSessionClose,session,reason)
            policy.requireOpen(session!!)
            val declared = policy.money(amount)
            if (now.isBefore(session.openedAt)) throw CashMutationException(CashMutationFailure.Validation)
            role(connection, RuntimeCashRole.Projection)
            writer.closeCashSession(connection,session.id,declared,now)
            role(connection, RuntimeCashRole.Application)
            audit(connection,actor,CashAuditEventType.CASH_SESSION_CLOSED,session.id,reason)
            session.copy(status=CashSessionStatus.CLOSED,closingCashDeclared=declared,closedAt=now)
        }
    }

    override fun expense(staff: AuthenticatedStaff, expense: ExpenseRecord): CashMutationResult<ExpenseRecord> = outcome {
        transaction { connection ->
            role(connection, RuntimeCashRole.Projection)
            val session = sessions.lock(connection,expense.cashSessionId)
            role(connection, RuntimeCashRole.Application)
            val actor = authority.actor(connection,staff.id)
            authorize(actor,StaffPermission.ExpenseRecord,session,expense.reason)
            policy.requireOpen(session!!)
            val amount = policy.money(expense.amount, positive=true)
            if (expense.paymentMethod == PaymentMethod.UNKNOWN || expense.reason.isBlank() || expense.reason.length > 500 || expense.category.isBlank() || expense.category.length > 80)
                throw CashMutationException(CashMutationFailure.Validation)
            val id = writer.insertExpense(connection,session.id,expense.category,amount,expense.reason,expense.paymentMethod.name,actor.id.value,expense.accruedAt)
            writer.insertAudit(connection,actor.id.value,CashAuditEventType.EXPENSE_RECORDED.name,"expense",id,detail=expense.reason)
            expense.copy(id=id,amount=amount,actorId=actor.id.value)
        }
    }

    private fun authorize(staff: AuthenticatedStaff, permission: StaffPermission, session: CashSession?, reason: String?) {
        policy.authorize(staff,permission,session,reason)?.let { throw CashMutationException(it,CashRejectionSource.Authorization) }
        if (reason != null && reason.length > 500) throw CashMutationException(CashMutationFailure.Validation)
    }
    private fun audit(connection: Connection, staff: AuthenticatedStaff, event: CashAuditEventType, id: Long, reason: String?) =
        writer.insertAudit(connection,staff.id.value,event.name,"cash_session",id,detail=reason?.takeIf { it.isNotBlank() } ?: "own cash session")
    private fun <T> outcome(block: () -> T): CashMutationResult<T> = try { CashMutationResult.Applied(block()) }
        catch (error: CashMutationException) { CashMutationResult.Rejected(error.failure,error.source) }
        catch (error: SQLException) { CashMutationResult.Rejected(CashMutationFailure.Unavailable) }
    private fun <T> transaction(block: (Connection) -> T): T = source.connection.use { connection ->
        connection.autoCommit=false
        connection.transactionIsolation=Connection.TRANSACTION_READ_COMMITTED
        try { val result=block(connection);connection.commit();result }
        catch (error: Exception) { connection.rollback();throw error }
    }
    private fun role(connection: Connection, role: RuntimeCashRole) { connection.createStatement().use { it.execute("SET LOCAL ROLE ${role.sqlName}") } }
}

private enum class RuntimeCashRole(val sqlName: String) { Application("blackstore_app"), Projection("blackstore_projection_worker") }

/** Locks are acquired before re-reading the staff authority so a waiting request uses current values. */
private class LoadLockedCashSession {
    fun lock(connection: Connection, id: Long): CashSession? = connection.prepareStatement("SELECT * FROM cash_session_projection WHERE id=? FOR UPDATE").use { statement ->
        statement.setLong(1,id);statement.executeQuery().use { rows -> if(rows.next()) rows.session() else null }
    }
    fun blocking(connection: Connection, terminal: Long, cashier: Long): List<CashSession> = connection.prepareStatement("SELECT * FROM cash_session_projection WHERE status='OPEN' AND (terminal_id=? OR cashier_id=?)").use { statement ->
        statement.setLong(1,terminal);statement.setLong(2,cashier);statement.executeQuery().use { rows -> buildList { while(rows.next()) add(rows.session()) } }
    }
    private fun ResultSet.session() = CashSession(getLong("id"),getLong("terminal_id"),getLong("cashier_id"),getTimestamp("opened_at").toInstant(),getBigDecimal("opening_cash"),CashSessionStatus.fromWire(getString("status")),getTimestamp("closed_at")?.toInstant(),getBigDecimal("closing_cash_declared"))
}

private class LoadCashAuthority {
    fun actor(connection: Connection, id: StaffUserId): AuthenticatedStaff = user(connection,id) ?: throw CashMutationException(CashMutationFailure.Forbidden,CashRejectionSource.Authorization)
    fun eligibleOwner(connection: Connection, id: StaffUserId) {
        val owner=user(connection,id) ?: throw CashMutationException(CashMutationFailure.NotVisible,CashRejectionSource.Authorization)
        if (!StaffAuthorizationPolicy().permits(owner.role,StaffPermission.CashSessionOpen)) throw CashMutationException(CashMutationFailure.NotVisible,CashRejectionSource.Authorization)
    }
    private fun user(connection: Connection, id: StaffUserId): AuthenticatedStaff? = connection.prepareStatement("SELECT display_name,role_code,active FROM staff_users WHERE id=?").use { statement ->
        statement.setLong(1,id.value);statement.executeQuery().use { rows ->
            if(!rows.next() || !rows.getBoolean("active")) null
            else AuthenticatedStaff(id,rows.getString("display_name"),StaffRole.fromWire(rows.getString("role_code")))
        }
    }
}
