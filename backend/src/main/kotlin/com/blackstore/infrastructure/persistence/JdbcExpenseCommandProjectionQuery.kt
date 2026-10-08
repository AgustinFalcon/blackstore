package com.blackstore.infrastructure.persistence

import com.blackstore.application.accounting.AccountingReceiptAccessPolicy
import com.blackstore.application.accounting.ExpenseCommandProjectionQuery
import com.blackstore.domain.accounting.*
import com.blackstore.domain.cash.*
import com.blackstore.domain.identity.*
import com.blackstore.domain.sales.PaymentMethod
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** No writer roles, locks, audit writes or lifecycle admission: historical recovery is read-only. */
@Component
@ConditionalOnProperty(name = ["blackstore.persistence.enabled"], havingValue = "true")
class JdbcExpenseCommandProjectionQuery(private val source: DataSource) : ExpenseCommandProjectionQuery {
    private val json = jacksonObjectMapper()
    private val access = AccountingReceiptAccessPolicy()
    private val evidence = ExpenseProjectionEvidencePolicy()

    override fun read(session: ResolvedStaffSession, commandId: UUID): ExpenseCommandProjectionResult = try {
        source.connection.use { c ->
            c.autoCommit = false
            c.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
            c.isReadOnly = true
            try {
                c.createStatement().use { it.execute("SET LOCAL ROLE blackstore_app") }
                val result = snapshot(c, session, commandId)
                c.commit()
                result
            } catch (error: Exception) { c.rollback(); throw error }
        }
    } catch (error: StaffSecurityException) { throw error }
      catch (_: Exception) { ExpenseCommandProjectionResult.Unavailable }

    private fun snapshot(c: Connection, session: ResolvedStaffSession, id: UUID): ExpenseCommandProjectionResult {
        val snapshot = rows(c, "SELECT statement_timestamp(),pg_current_snapshot()::text") {
            val at = it.getTimestamp(1).toInstant()
            ExpenseProjectionSnapshot(at, at, it.getString(2))
        }.single()
        val actor = authority(c, session, snapshot.asOf)
        val header = rows(c, "SELECT * FROM accounting_command_receipts WHERE command_id=?", id) { Header(
            it.getLong("actor_id"), it.getLong("cash_session_id"), AccountingCommandKind.fromWire(it.getString("command_kind")),
            it.getString("payload_hash"), it.getString("result"), it.getString("outcome"), it.getInt("accounting_version"),
            it.getTimestamp("recorded_at").toInstant(), it.getObject("sale_id") != null)
        }.singleOrNull() ?: return ExpenseCommandProjectionResult.NotFound
        val cash = rows(c, "SELECT * FROM cash_session_projection WHERE id=?", header.cashId) {
            if (actor.role == StaffRole.CASHIER && it.getLong("cashier_id") != actor.id.value) return@rows null
            CashSession(it.getLong("id"), it.getLong("terminal_id"), it.getLong("cashier_id"), it.getTimestamp("opened_at").toInstant(),
                it.getBigDecimal("opening_cash"), CashSessionStatus.fromWire(it.getString("status")),
                it.getTimestamp("closed_at")?.toInstant(), it.getBigDecimal("closing_cash_declared"))
        }.singleOrNull()
        if (access.authorize(actor, cash, AccountingCommandKind.EXPENSE_RECORD) != null) return ExpenseCommandProjectionResult.NotFound
        if (header.kind != AccountingCommandKind.EXPENSE_RECORD) return ExpenseCommandProjectionResult.NotFound
        if (header.outcome != "COMMITTED" || header.version != 2 || header.hasSale) return ExpenseCommandProjectionResult.Unavailable
        val receipt = decode(id, header)
        val expenseSource = rows(c, "SELECT * FROM expenses WHERE id=?", receipt.expenseId) {
            require(it.getTimestamp("paid_at") == null)
            ExpenseProjectionExpense(it.getLong("id"), it.getLong("cash_session_id"), it.getString("category"), it.getBigDecimal("amount"),
                it.getLong("created_by"), it.getTimestamp("accrued_at").toInstant()) to PaymentMethod.fromWire(it.getString("payment_method"))
        }.singleOrNull() ?: return ExpenseCommandProjectionResult.Unavailable
        val expense = expenseSource.first
        val settlements = rows(c, "SELECT * FROM expense_settlements WHERE command_id=?", id) {
            ExpenseProjectionSettlement(it.getLong("id"), it.getLong("expense_id"), it.getLong("cash_session_id"), it.getBigDecimal("amount"),
                PaymentMethod.fromWire(it.getString("payment_method")), it.getLong("actor_id"), it.getObject("command_id", UUID::class.java),
                it.getTimestamp("paid_at").toInstant())
        }
        val postings = postings(c, "command_id=?", id)
        val originals = postings(c, "expense_id=? AND event_type='EXPENSE_ACCRUAL'", expense.id)
        val original = originals.singleOrNull() ?: return ExpenseCommandProjectionResult.Unavailable
        if (expenseSource.second == PaymentMethod.UNKNOWN || expenseSource.second != original.posting.method)
            return ExpenseCommandProjectionResult.Unavailable
        if (original.commandId != id) {
            // Validate the previous accrual's own receipt, not just a convenient posting matching its amount.
            val previous = rows(c, "SELECT * FROM accounting_command_receipts WHERE command_id=?", original.commandId) { Header(
                it.getLong("actor_id"), it.getLong("cash_session_id"), AccountingCommandKind.fromWire(it.getString("command_kind")),
                it.getString("payload_hash"), it.getString("result"), it.getString("outcome"), it.getInt("accounting_version"),
                it.getTimestamp("recorded_at").toInstant(), it.getObject("sale_id") != null)
            }.singleOrNull() ?: return ExpenseCommandProjectionResult.Unavailable
            if (previous.outcome != "COMMITTED" || previous.version != 2 || previous.hasSale) return ExpenseCommandProjectionResult.Unavailable
            val prior = evidence.project(decode(original.commandId, previous), expense, emptyList(),
                postings(c, "command_id=?", original.commandId), originals, snapshot)
            if ((prior as? ExpenseCommandProjectionResult.Found)?.projection?.operation != ExpenseProjectionOperation.Accrue)
                return ExpenseCommandProjectionResult.Unavailable
        }
        return evidence.project(receipt, expense, settlements, postings, originals, snapshot)
    }

    private fun authority(c: Connection, session: ResolvedStaffSession, now: Instant): AuthenticatedStaff {
        val current = rows(c, "SELECT u.id,u.display_name,u.role_code,u.active,s.revoked_at,s.expires_at,s.last_used_at FROM staff_sessions s JOIN staff_users u ON u.id=s.user_id WHERE s.token_digest=? AND s.user_id=?",
            session.session.digest, session.staff.id.value) {
            if (it.getTimestamp("revoked_at") != null || now >= it.getTimestamp("expires_at").toInstant() ||
                now >= it.getTimestamp("last_used_at").toInstant().plusSeconds(1800))
                throw StaffSecurityException(StaffSecurityFailure.SESSION_INVALID)
            it.getBoolean("active") to AuthenticatedStaff(StaffUserId(it.getLong("id")), it.getString("display_name"), StaffRole.fromWire(it.getString("role_code")))
        }.singleOrNull() ?: throw StaffSecurityException(StaffSecurityFailure.SESSION_INVALID)
        if (!current.first || !StaffAuthorizationPolicy().permits(current.second.role, StaffPermission.AccountingCommandRead) ||
            !StaffAuthorizationPolicy().permits(current.second.role, StaffPermission.ExpenseRecord))
            throw StaffSecurityException(StaffSecurityFailure.FORBIDDEN)
        return current.second
    }

    private fun decode(id: UUID, h: Header): AccountingCommandReceipt {
        val result = json.readTree(h.result)
        fun nullableId(key: String): Long? {
            val node = result.get(key) ?: error("missing receipt field")
            return if (node.isNull) null else { require(node.isIntegralNumber && node.canConvertToLong()); node.longValue() }
        }
        require(result.path("ledgerEventIds").isArray && result.path("committedAt").isTextual)
        val at = Instant.parse(result.path("committedAt").textValue())
        require(at == h.at && result.path("closeSnapshot").isNull)
        return AccountingCommandReceipt(id, h.actorId, h.kind, h.cashId, h.hash,
            result.path("ledgerEventIds").map { require(it.isIntegralNumber && it.canConvertToLong()); it.longValue() }, at,
            nullableId("saleId"), nullableId("paymentId"), nullableId("expenseId"), nullableId("settlementId"))
    }

    private fun postings(c: Connection, predicate: String, value: Any): List<ExpenseProjectionPosting> = rows(c,
        "SELECT * FROM cash_ledger_events WHERE $predicate ORDER BY id", value) {
        require(it.getObject("sale_id") == null && it.getObject("payment_id") == null && it.getObject("original_event_id") == null)
        ExpenseProjectionPosting(it.getLong("id"), it.getObject("command_id", UUID::class.java), it.getLong("cash_session_id"),
            (it.getObject("expense_id") as? Number)?.toLong(), it.getLong("actor_id"), LedgerPosting(
                LedgerEventKind.fromWire(it.getString("event_type")), LedgerComponent.fromWire(it.getString("component")),
                PaymentMethod.fromWire(it.getString("payment_method")), it.getBigDecimal("amount_delta"),
                LedgerOrigin(LedgerOriginKind.fromWire(it.getString("origin_kind")), it.getString("origin_id"))),
            it.getTimestamp("occurred_at").toInstant(), it.getInt("accounting_version"))
    }
    private fun <T> rows(c: Connection, sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> =
        c.prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { result -> buildList { while (result.next()) add(map(result)) } }
        }
    private data class Header(val actorId: Long, val cashId: Long, val kind: AccountingCommandKind, val hash: String,
        val result: String, val outcome: String, val version: Int, val at: Instant, val hasSale: Boolean)
}
