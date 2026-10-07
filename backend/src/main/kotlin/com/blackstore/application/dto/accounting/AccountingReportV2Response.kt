package com.blackstore.application.dto.accounting

import com.blackstore.domain.reports.*
import com.blackstore.domain.sales.PaymentMethod
import com.fasterxml.jackson.annotation.JsonValue
import java.math.BigDecimal
import java.time.Instant

/** Typed response wrappers preserve the domain wire vocabulary without framework in domain. */
data class ReportCompletenessV2(val state: CompletenessStateV2, val causes: Set<CompletenessCauseV2>)
data class CompletenessStateV2(private val state: DataCompleteness) { @JsonValue fun wire() = state.wire }
data class CompletenessCauseV2(private val cause: CompletenessCause) { @JsonValue fun wire() = cause.wire }
data class ReportMetricV2(private val metric: AccountingMetric) { @JsonValue fun wire() = metric.wire }
data class ReportFormulaKindV2(private val kind: FormulaKind) { @JsonValue fun wire() = kind.wire }
data class ReportReconciliationV2(val expectedCash: BigDecimal?, val declaredCash: BigDecimal?, val difference: BigDecimal?,
    val outcome: ReconciliationOutcomeV2, val localWatermark: Long?)
data class ReportFormulaV2(val kind: ReportFormulaKindV2, val version: FormulaVersion, val value: BigDecimal?, val completeness: ReportCompletenessV2)
data class ReportFinancialTotalsV2(val collected: BigDecimal?, val refunds: BigDecimal?, val feesPaid: BigDecimal?, val expensesPaid: BigDecimal?, val operatingCashFlow: BigDecimal?)
data class AccountingReportV2Response(val periodKind: ReportPeriodKind, val cashSessionId: Long?, val localDate: java.time.LocalDate?,
    val bounds: ReportBounds, val cutoff: Instant, val snapshot: String, val zone: String, val zoneVersion: String, val zoneEffectiveAt: Instant,
    val accountingVersion: Int, val provisional: Boolean, val grossSales: BigDecimal?, val discounts: BigDecimal?, val netSales: BigDecimal?,
    val totalsByMethod: Map<PaymentMethod, ReportFinancialTotalsV2>, val completeness: Map<ReportMetricV2, ReportCompletenessV2>,
    val formula: ReportFormulaV2, val reconciliation: ReportReconciliationV2?)
object AccountingReportV2ResponseTranslator {
    private fun completeness(value: MetricCompleteness) = ReportCompletenessV2(CompletenessStateV2(value.state), value.causes.map(::CompletenessCauseV2).toSet())
    fun translate(report: AccountingReport): AccountingReportV2Response = AccountingReportV2Response(
        when (report.period) { is ReportPeriod.Shift -> ReportPeriodKind.SHIFT; is ReportPeriod.Day -> ReportPeriodKind.DAY; ReportPeriod.Unknown -> ReportPeriodKind.Unknown },
        (report.period as? ReportPeriod.Shift)?.sessionId, (report.period as? ReportPeriod.Day)?.localDate,
        report.bounds, report.cutoff, report.snapshot, report.zone.zone.id, report.zone.version, report.zone.effectiveAt,
        report.accountingVersion, report.provisional, report.grossSales, report.discounts, report.netSales, report.totalsByMethod.mapValues { (_, totals) ->
            fun available(metric: AccountingMetric) = report.completeness[metric]?.state in setOf(DataCompleteness.Complete, DataCompleteness.Partial)
            val allAvailable = listOf(AccountingMetric.Collected, AccountingMetric.Refunds, AccountingMetric.FeesPaid, AccountingMetric.ExpensesPaid).all(::available)
            ReportFinancialTotalsV2(totals.collected.takeIf { available(AccountingMetric.Collected) }, totals.refunds.takeIf { available(AccountingMetric.Refunds) },
                totals.feesPaid.takeIf { available(AccountingMetric.FeesPaid) }, totals.expensesPaid.takeIf { available(AccountingMetric.ExpensesPaid) },
                totals.operatingCashFlow.takeIf { allAvailable }) },
        report.completeness.mapKeys { ReportMetricV2(it.key) }.mapValues { completeness(it.value) },
        ReportFormulaV2(ReportFormulaKindV2(report.formula.kind), report.formula.version, report.formula.value, completeness(report.formula.completeness)),
        report.reconciliation?.let { ReportReconciliationV2(it.expectedCash, it.declaredCash, it.difference,
            ReconciliationOutcomeV2.fromWire(it.outcome.wire), it.localWatermark) })
}
