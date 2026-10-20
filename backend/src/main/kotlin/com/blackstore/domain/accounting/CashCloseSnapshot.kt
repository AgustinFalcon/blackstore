package com.blackstore.domain.accounting

import com.blackstore.domain.sales.MoneyPolicy
import java.math.BigDecimal
import java.time.Instant

/** Immutable close result, also carried by the original command receipt on replay. */
data class CashCloseSnapshot(
    val declaredCash: BigDecimal,
    val expectedCash: BigDecimal?,
    val difference: BigDecimal?,
    val outcome: ReconciliationOutcome,
    val coverage: AccountingCoverage,
    val cutoff: Instant,
    val localWatermark: Long,
    val accountingVersion: Int = 2,
) {
    init {
        require(declaredCash.signum() >= 0 && localWatermark >= 0 && accountingVersion == 2)
        listOfNotNull(declaredCash, expectedCash, difference).forEach(MoneyPolicy::normalize)
        require(coverage != AccountingCoverage.Unknown && outcome != ReconciliationOutcome.Unknown)
        if (coverage == AccountingCoverage.LegacyIncomplete) {
            require(outcome == ReconciliationOutcome.Unavailable && expectedCash == null && difference == null)
        } else {
            require(expectedCash != null && difference != null && difference.compareTo(declaredCash - expectedCash) == 0)
            require(outcome == when (difference.signum()) {
                -1 -> ReconciliationOutcome.Shortage
                1 -> ReconciliationOutcome.Overage
                else -> ReconciliationOutcome.Balanced
            })
        }
    }
}
