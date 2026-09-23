package com.blackstore.domain.reports

import java.math.BigDecimal

data class CostFact(
    val amount: BigDecimal?,
    val source: String?,
    val version: String?,
    val effectiveAtPresent: Boolean,
) {
    val validated: Boolean
        get() = amount != null && amount.signum() >= 0 && !source.isNullOrBlank() && !version.isNullOrBlank() && effectiveAtPresent
}

data class ShiftFigures(
    val grossSales: BigDecimal,
    val discounts: BigDecimal,
    val refunds: BigDecimal,
    val collected: BigDecimal,
    val feesPaid: BigDecimal,
    val expensesPaid: BigDecimal,
    val costs: List<CostFact> = emptyList(),
) {
    val netSales: BigDecimal = grossSales.subtract(discounts)
    val operatingCashFlow: BigDecimal = collected.subtract(refunds).subtract(feesPaid).subtract(expensesPaid)

    val margin: BigDecimal?
        get() {
            if (costs.isEmpty() || costs.any { !it.validated }) return null
            val costTotal = costs.fold(BigDecimal.ZERO) { acc, cost -> acc.add(cost.amount) }
            return netSales.subtract(costTotal)
        }
}

data class ReportProjection(
    val formulaName: String,
    val version: String,
    val periodKind: String,
    val value: BigDecimal,
    val fiscalResult: Boolean = false,
    val freeCash: Boolean = false,
)

class ReportFormulas {

    fun contribution(figures: ShiftFigures, version: String, periodKind: String = "SHIFT"): ReportProjection =
        ReportProjection(
            formulaName = "CONTRIBUTION",
            version = version,
            periodKind = periodKind,
            value = figures.netSales.subtract(figures.feesPaid).subtract(figures.expensesPaid),
        )
}
