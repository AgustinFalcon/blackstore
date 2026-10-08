package com.blackstore.domain.reports

import com.blackstore.domain.accounting.LedgerEventKind
import com.blackstore.domain.identity.AuthenticatedStaff
import com.blackstore.domain.sales.PaymentMethod
import java.math.BigDecimal
import java.time.Instant

data class ReportFilters(val terminalId: Long? = null, val cashierId: Long? = null, val cashSessionId: Long? = null) {
    init { listOfNotNull(terminalId, cashierId, cashSessionId).forEach { require(it > 0) } }
}
data class AccountingReportRequest(val period: ReportPeriod, val filters: ReportFilters = ReportFilters(),
    val formula: FormulaKind = FormulaKind.Contribution, val version: FormulaVersion = FormulaVersion.V1)
data class ReportMovement(val kind: LedgerEventKind, val method: PaymentMethod, val amount: BigDecimal)
data class FinancialTotals(val collected: BigDecimal, val refunds: BigDecimal, val feesPaid: BigDecimal, val expensesPaid: BigDecimal) {
    val operatingCashFlow: BigDecimal get() = collected - refunds - feesPaid - expensesPaid
}
class FinancialTotalsPolicy {
    fun totals(movements: List<ReportMovement>): Map<PaymentMethod, FinancialTotals> = PaymentMethod.entries.filter { it != PaymentMethod.UNKNOWN }.associateWith { method ->
        fun sum(kind: LedgerEventKind) = movements.filter { it.method == method && it.kind == kind }.fold(BigDecimal("0.00")) { total, row -> total + row.amount.abs() }
        FinancialTotals(sum(LedgerEventKind.PAYMENT), sum(LedgerEventKind.REFUND), sum(LedgerEventKind.FEE), sum(LedgerEventKind.EXPENSE_PAID))
    }
}
class ReportExpectedCashPolicy {
    fun expected(movements: List<ReportMovement>, completeness: Collection<MetricCompleteness>): BigDecimal? {
        if (completeness.any { it.state != DataCompleteness.Complete } ||
            movements.any { it.kind == LedgerEventKind.Unknown || it.method == PaymentMethod.UNKNOWN } ||
            movements.count { it.kind == LedgerEventKind.OPENING } != 1) return null
        return movements.filter { it.method == PaymentMethod.CASH && it.kind != LedgerEventKind.EXPENSE_ACCRUAL }
            .fold(BigDecimal("0.00")) { total, row -> total + row.amount }
    }
}
data class ReportReconciliation(val expectedCash: BigDecimal?, val declaredCash: BigDecimal?, val difference: BigDecimal?,
    val outcome: com.blackstore.domain.accounting.ReconciliationOutcome, val localWatermark: Long?)
data class AccountingReport(val period: ReportPeriod, val bounds: ReportBounds, val cutoff: Instant, val snapshot: String,
    val zone: EffectiveReportZone, val accountingVersion: Int, val provisional: Boolean,
    val grossSales: BigDecimal?, val discounts: BigDecimal?, val netSales: BigDecimal?,
    val totalsByMethod: Map<PaymentMethod, FinancialTotals>, val completeness: Map<AccountingMetric, MetricCompleteness>,
    val formula: AccountingFormulaResult, val reconciliation: ReportReconciliation?)
enum class AccountingReportFailure { Validation, Forbidden, NotVisible, Unavailable, Unknown }
sealed class AccountingReportResult {
    data class Available(val report: AccountingReport) : AccountingReportResult()
    data class Rejected(val failure: AccountingReportFailure) : AccountingReportResult()
}
interface AccountingReportQuery { fun read(staff: AuthenticatedStaff, request: AccountingReportRequest): AccountingReportResult }
