package com.blackstore.application.counter

import com.blackstore.domain.cash.RoleAuthorizationPolicy
import com.blackstore.domain.cash.SessionAction
import com.blackstore.domain.cash.StaffRole
import com.blackstore.domain.ledger.ExpenseRecord
import com.blackstore.domain.port.out.counter.CounterEntryStore
import com.blackstore.domain.reports.ReportFormulas
import com.blackstore.domain.reports.ShiftFigures
import com.blackstore.domain.sales.PaymentBook
import com.blackstore.domain.sales.PaymentMethod
import com.blackstore.domain.sales.PaymentRecord
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

@Service
class CounterApplicationService(
    private val store: CounterEntryStore,
) {
    private val payments = PaymentBook()
    private val roles = RoleAuthorizationPolicy()
    private val ids = AtomicLong(1)

    fun capture(
        operationId: String,
        method: PaymentMethod,
        amount: BigDecimal,
        feeAmount: BigDecimal,
    ): PaymentRecord {
        val payment = payments.capture(ids.getAndIncrement(), method, amount, feeAmount)
        return store.savePayment(payment, operationId)
    }

    fun reverse(
        operationId: String,
        paymentId: Long,
        actorId: Long,
        role: StaffRole,
        reason: String,
        evidenceRef: String,
    ): PaymentRecord {
        roles.assertAllowed(role, SessionAction.OPERATE, actorId, actorId)
        val original = store.findPayment(paymentId) ?: throw IllegalArgumentException("payment $paymentId was not found")
        val reversal =
            payments.reverse(
                original = original,
                reversalId = ids.getAndIncrement(),
                actorId = actorId,
                reason = reason,
                evidenceRef = evidenceRef,
            )
        return store.savePayment(reversal, operationId)
    }

    fun addExpense(
        actorId: Long,
        role: StaffRole,
        cashSessionId: Long,
        category: String,
        amount: BigDecimal,
        reason: String,
        method: PaymentMethod,
        now: Instant = Instant.now(),
    ): ExpenseRecord {
        roles.assertAllowed(role, SessionAction.OPERATE, actorId, actorId)
        val expense =
            ExpenseRecord(
                id = ids.getAndIncrement(),
                cashSessionId = cashSessionId,
                category = category,
                amount = amount,
                reason = reason,
                paymentMethod = method,
                actorId = actorId,
                accruedAt = now,
            )
        store.saveExpense(expense)
        return expense
    }

    fun shiftReport(): ShiftReport = report("SHIFT")

    fun dailyReport(): ShiftReport = report("DAY")

    private fun report(periodKind: String): ShiftReport {
        val figures = store.figures()
        val projection = ReportFormulas().contribution(figures, version = "v1", periodKind = periodKind)
        return ShiftReport(
            figures,
            projection.formulaName,
            projection.version,
            projection.fiscalResult,
            projection.freeCash,
            projection.periodKind,
        )
    }
}

data class ShiftReport(
    val figures: ShiftFigures,
    val formulaName: String,
    val formulaVersion: String,
    val fiscalResult: Boolean,
    val freeCash: Boolean,
    val periodKind: String,
)
