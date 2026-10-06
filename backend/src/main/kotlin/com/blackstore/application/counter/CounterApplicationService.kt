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
import com.blackstore.domain.sales.PaymentTransitionPolicy
import com.blackstore.domain.sales.MoneyPolicy
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.port.out.sales.SaleOperationQuery
import com.blackstore.application.sales.LocalSaleCoordinator
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import com.blackstore.application.identity.AuthorizeStaffAction
import com.blackstore.domain.identity.*

@Service
class CounterApplicationService(
    private val store: CounterEntryStore,
    private val saleQuery: SaleOperationQuery,
    private val coordinator: LocalSaleCoordinator,
    private val authorization: AuthorizeStaffAction? = null,
) {
    private val payments = PaymentBook()
    private val roles = RoleAuthorizationPolicy()
    private val ids = AtomicLong(1)

    fun capture(staff: AuthenticatedStaff, identity: OperationQuadruple, method: PaymentMethod, amount: BigDecimal, feeAmount: BigDecimal, reason: String?): PaymentRecord {
        authority().sale(staff,StaffPermission.PaymentCapture,identity,reason)
        return capture(identity,method,amount,feeAmount,staff.id.value,reason)
    }
    fun reverse(staff: AuthenticatedStaff, identity: OperationQuadruple, paymentId: Long, reason: String, evidenceRef: String): PaymentRecord {
        authority().payment(staff,paymentId,identity,reason)
        require(evidenceRef.isNotBlank()) { "reversal evidence is required" }
        return reverse(identity,paymentId,staff.id.value,staff.role,reason,evidenceRef)
    }
    fun addExpense(staff: AuthenticatedStaff, cashSessionId: Long, category: String, amount: BigDecimal, reason: String, method: PaymentMethod): ExpenseRecord {
        authority().cash(staff,StaffPermission.ExpenseRecord,cashSessionId,reason)
        return addExpense(staff.id.value,staff.role,cashSessionId,category,amount,reason,method)
    }
    fun shiftReport(staff: AuthenticatedStaff): ShiftReport { authority().permission(staff,StaffPermission.ShiftReportRead); return shiftReport() }
    fun dailyReport(staff: AuthenticatedStaff): ShiftReport { authority().permission(staff,StaffPermission.DailyReportRead); return dailyReport() }
    private fun authority()=authorization ?: throw StaffSecurityException(StaffSecurityFailure.IDENTITY_UNAVAILABLE)

    fun capture(
        operationId: String,
        method: PaymentMethod,
        amount: BigDecimal,
        feeAmount: BigDecimal,
    ): PaymentRecord {
        val sale = saleQuery.resolveSale(operationId) ?: throw IllegalArgumentException("sale missing or ambiguous")
        return capture(sale.quadruple, method, amount, feeAmount)
    }

    fun capture(identity: OperationQuadruple, method: PaymentMethod, amount: BigDecimal, feeAmount: BigDecimal, actorId: Long? = null, reason: String? = null): PaymentRecord =
        coordinator.coordinate(identity) {
            val sale = saleQuery.findSale(identity) ?: throw IllegalArgumentException("sale missing or ambiguous")
            PaymentTransitionPolicy().capture(sale, store.paymentLedger(identity), method, amount, feeAmount).assertAllowed()
            store.savePayment(payments.capture(ids.getAndIncrement(), method, MoneyPolicy.normalize(amount), MoneyPolicy.normalize(feeAmount)).copy(actorId = actorId, reason = reason), identity)
        }

    fun reverse(
        operationId: String,
        paymentId: Long,
        actorId: Long,
        role: StaffRole,
        reason: String,
        evidenceRef: String,
    ): PaymentRecord {
        val sale = saleQuery.resolveSale(operationId) ?: throw IllegalArgumentException("sale missing or ambiguous")
        return reverse(sale.quadruple, paymentId, actorId, role, reason, evidenceRef)
    }

    fun reverse(identity: OperationQuadruple, paymentId: Long, actorId: Long, role: StaffRole, reason: String, evidenceRef: String): PaymentRecord =
        coordinator.coordinate(identity) {
        roles.assertAllowed(role, SessionAction.OPERATE, actorId, actorId)
        val sale = saleQuery.findSale(identity) ?: throw IllegalArgumentException("sale missing or ambiguous")
        PaymentTransitionPolicy().reverse(sale, store.paymentLedger(identity), paymentId).assertAllowed()
        val original = store.findPayment(paymentId) ?: throw IllegalArgumentException("payment $paymentId was not found")
        val reversal =
            payments.reverse(
                original = original,
                reversalId = ids.getAndIncrement(),
                actorId = actorId,
                reason = reason,
                evidenceRef = evidenceRef,
            )
        store.savePayment(reversal, identity)
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
