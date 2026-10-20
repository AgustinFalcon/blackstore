package com.blackstore.domain.reports

import com.blackstore.domain.sales.MoneyPolicy
import java.math.BigDecimal

enum class FormulaKind(val wire: String) { Contribution("CONTRIBUTION"), ReinvestmentSuggestion("REINVESTMENT_SUGGESTION"), OwnerSurplusEstimate("OWNER_SURPLUS_ESTIMATE"), Unknown("UNKNOWN");
    companion object { fun fromWire(value: String?): FormulaKind = entries.firstOrNull { it.wire == value } ?: Unknown }
}
enum class FormulaVersion { V1, Unknown;
    companion object { fun fromWire(value: String?): FormulaVersion = entries.firstOrNull { it.name == value } ?: Unknown }
}
enum class DataCompleteness(val wire: String) { Complete("COMPLETE"), Partial("PARTIAL"), LegacyIncomplete("LEGACY_INCOMPLETE"), Unavailable("UNAVAILABLE"), Unknown("UNKNOWN");
    companion object { fun fromWire(value: String?): DataCompleteness = entries.firstOrNull { it.wire == value } ?: Unknown }
}
enum class CompletenessCause(val wire: String) {
    LegacyActivity("LEGACY_ACTIVITY"), BeforeActivation("BEFORE_ACTIVATION"), MissingSource("MISSING_SOURCE"),
    UnknownSource("UNKNOWN_SOURCE"), FormulaNotApproved("FORMULA_NOT_APPROVED"), UnknownFormula("UNKNOWN_FORMULA"), Unknown("UNKNOWN");
    companion object { fun fromWire(value: String?): CompletenessCause = entries.firstOrNull { it.wire == value } ?: Unknown }
}
enum class AccountingMetric(val wire: String) { NetSales("NET_SALES"), Collected("COLLECTED"), Refunds("REFUNDS"), FeesPaid("FEES_PAID"), ExpensesPaid("EXPENSES_PAID"), Contribution("CONTRIBUTION"), Unknown("UNKNOWN");
    companion object { fun fromWire(value: String?): AccountingMetric = entries.firstOrNull { it.wire == value } ?: Unknown }
}

data class MetricCompleteness(val state: DataCompleteness, val causes: Set<CompletenessCause>) {
    init {
        require((state == DataCompleteness.Complete) == causes.isEmpty())
    }
    companion object { val Complete = MetricCompleteness(DataCompleteness.Complete, emptySet()) }
}

/** Universe evidence must come from sessions/sales/payments/expenses, independently of postings. */
data class ReportSourceCoverage(val relevantSources: Int, val evidencedSources: Int, val legacySources: Int, val unknownSources: Int) {
    init {
        require(relevantSources >= 0 && evidencedSources >= 0 && legacySources >= 0 && unknownSources >= 0)
        require(evidencedSources.toLong() + legacySources + unknownSources <= relevantSources)
    }
}

class ReportCoveragePolicy {
    fun assess(universe: ReportSourceCoverage?, bounds: ReportBounds, activatedAt: java.time.Instant?): MetricCompleteness {
        if (universe == null) return MetricCompleteness(DataCompleteness.Unavailable, setOf(CompletenessCause.MissingSource))
        val causes = buildSet {
            if (activatedAt == null || bounds.start < activatedAt) add(CompletenessCause.BeforeActivation)
            if (universe.legacySources > 0) add(CompletenessCause.LegacyActivity)
            if (universe.unknownSources > 0) add(CompletenessCause.UnknownSource)
            if (universe.evidencedSources.toLong() + universe.legacySources + universe.unknownSources < universe.relevantSources) add(CompletenessCause.MissingSource)
        }
        val state = when {
            causes.isEmpty() -> DataCompleteness.Complete
            universe.evidencedSources > 0 -> DataCompleteness.Partial
            CompletenessCause.LegacyActivity in causes || CompletenessCause.BeforeActivation in causes -> DataCompleteness.LegacyIncomplete
            else -> DataCompleteness.Unavailable
        }
        return MetricCompleteness(state, causes)
    }
}

data class AccountingFormulaInputs(val netSales: BigDecimal, val feesPaid: BigDecimal, val expensesPaid: BigDecimal, val completeness: MetricCompleteness) {
    init { listOf(netSales, feesPaid, expensesPaid).forEach { require(it.signum() >= 0); MoneyPolicy.normalize(it) } }
}

data class AccountingFormulaResult(val kind: FormulaKind, val version: FormulaVersion, val value: BigDecimal?, val completeness: MetricCompleteness)

class ReportFormula {
    fun evaluate(kind: FormulaKind, version: FormulaVersion, inputs: AccountingFormulaInputs): AccountingFormulaResult {
        val unapproved = kind != FormulaKind.Contribution || version != FormulaVersion.V1
        if (unapproved) {
            val cause = if (kind == FormulaKind.Unknown || version == FormulaVersion.Unknown) CompletenessCause.UnknownFormula else CompletenessCause.FormulaNotApproved
            return AccountingFormulaResult(kind, version, null, MetricCompleteness(DataCompleteness.Unavailable, inputs.completeness.causes + cause))
        }
        val value = if (inputs.completeness.state in setOf(DataCompleteness.Complete, DataCompleteness.Partial)) MoneyPolicy.normalize(inputs.netSales - inputs.feesPaid - inputs.expensesPaid) else null
        return AccountingFormulaResult(kind, version, value, inputs.completeness)
    }
}
