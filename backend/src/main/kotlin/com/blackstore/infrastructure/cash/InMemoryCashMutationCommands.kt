package com.blackstore.infrastructure.cash

import com.blackstore.domain.cash.*
import com.blackstore.domain.identity.*
import com.blackstore.domain.ledger.ExpenseRecord
import com.blackstore.infrastructure.counter.InMemoryCounterEntryStore
import com.blackstore.domain.port.out.cash.CashMutationCommands
import com.blackstore.domain.sales.PaymentMethod
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/** Fixture implementation; persistence mode never selects this bean. */
@Component
@ConditionalOnProperty(name=["blackstore.persistence.enabled"],havingValue="false",matchIfMissing=true)
class InMemoryCashMutationCommands(private val cash: InMemoryCashSessionStore, private val counter: InMemoryCounterEntryStore, private val users: StaffUserRepository) : CashMutationCommands {
    private val policy=CashMutationPolicy()
    private val expenseIds=AtomicLong(1)
    override fun open(staff: AuthenticatedStaff, terminalId: Long, cashierId: Long, amount: BigDecimal, reason: String?, now: Instant) = command {
        val actor=actor(staff)
        val candidate=CashSession(0,terminalId,cashierId,now,policy.money(amount))
        authorize(actor,StaffPermission.CashSessionOpen,candidate,reason)
        val owner=users.findById(StaffUserId(cashierId))
        if(owner?.state != StaffAccountState.ACTIVE || !StaffAuthorizationPolicy().permits(owner.role,StaffPermission.CashSessionOpen)) throw CashMutationException(CashMutationFailure.NotVisible,CashRejectionSource.Authorization)
        val blockers=cash.list().filter { it.status==CashSessionStatus.OPEN && (it.terminalId==terminalId || it.cashierId==cashierId) }
        if(blockers.any { policy.authorize(actor,StaffPermission.CashSessionOpen,it,reason)==CashMutationFailure.NotVisible })
            throw CashMutationException(CashMutationFailure.NotVisible,CashRejectionSource.Authorization)
        if(blockers.isNotEmpty()) {
            blockers.forEach { authorize(actor,StaffPermission.CashSessionOpen,it,reason) }
            throw CashMutationException(CashMutationFailure.Conflict)
        }
        val stored=cash.save(candidate)
        cash.appendClosureAudit(CashAuditEvent(stored.id,actor.id.value,CashAuditEventType.CASH_SESSION_OPENED,reason ?: "own cash session"))
        stored
    }
    override fun close(staff: AuthenticatedStaff, sessionId: Long, amount: BigDecimal, reason: String?, now: Instant) = command {
        val actor=actor(staff)
        val session=cash.list().firstOrNull { it.id==sessionId }
        authorize(actor,StaffPermission.CashSessionClose,session,reason)
        policy.requireOpen(session!!)
        val (closed,audit)=CashSessionBook().close(session,policy.money(amount),now,actor.id.value,reason?.takeIf { it.isNotBlank() } ?: "own cash session")
        cash.save(closed)
        cash.appendClosureAudit(audit)
        closed
    }
    override fun expense(staff: AuthenticatedStaff, expense: ExpenseRecord) = command {
        val actor=actor(staff)
        val session=cash.list().firstOrNull { it.id==expense.cashSessionId }
        authorize(actor,StaffPermission.ExpenseRecord,session,expense.reason)
        policy.requireOpen(session!!)
        if(expense.paymentMethod==PaymentMethod.UNKNOWN || expense.category.length>80 || expense.reason.length>500) throw CashMutationException(CashMutationFailure.Validation)
        val stored=expense.copy(id=expenseIds.getAndIncrement(),amount=policy.money(expense.amount,true),actorId=actor.id.value)
        counter.saveExpense(stored)
        cash.appendClosureAudit(CashAuditEvent(session.id,actor.id.value,CashAuditEventType.EXPENSE_RECORDED,expense.reason))
        stored
    }
    private fun actor(staff: AuthenticatedStaff): AuthenticatedStaff {
        val user=users.findById(staff.id)
        if(user?.state != StaffAccountState.ACTIVE) throw CashMutationException(CashMutationFailure.Forbidden,CashRejectionSource.Authorization)
        return AuthenticatedStaff(user.id,user.displayName,user.role)
    }
    private fun authorize(staff: AuthenticatedStaff, permission: StaffPermission, session: CashSession?, reason: String?) {
        policy.authorize(staff,permission,session,reason)?.let { throw CashMutationException(it,CashRejectionSource.Authorization) }
        if(reason != null && reason.length>500) throw CashMutationException(CashMutationFailure.Validation)
    }
    private fun <T> command(action: () -> T): CashMutationResult<T> = synchronized(this) {
        try { CashMutationResult.Applied(cash.atomic { counter.atomicExpenses(action) }) } catch (error: CashMutationException) { CashMutationResult.Rejected(error.failure,error.source) }
    }
}
