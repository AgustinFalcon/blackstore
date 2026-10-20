package com.blackstore.domain.accounting

import com.blackstore.domain.sales.MoneyPolicy
import java.math.BigDecimal

enum class AccountingCoverage(val wire: String) { CompleteFromOpening("COMPLETE_FROM_OPENING"), LegacyIncomplete("LEGACY_INCOMPLETE"), Unknown("UNKNOWN");
    companion object { fun fromWire(value: String?): AccountingCoverage = entries.firstOrNull { it.wire == value } ?: Unknown }
}
enum class ReconciliationOutcome(val wire: String) { Balanced("BALANCED"), Shortage("SHORTAGE"), Overage("OVERAGE"), Unavailable("UNAVAILABLE"), Unknown("UNKNOWN");
    companion object { fun fromWire(value: String?): ReconciliationOutcome = entries.firstOrNull { it.wire == value } ?: Unknown }
}

data class CashReconciliation private constructor(
    val declaredCash: BigDecimal,
    val expectedCash: BigDecimal?,
    val difference: BigDecimal?,
    val outcome: ReconciliationOutcome,
) {
    companion object {
        internal fun calculated(declared: BigDecimal, expected: BigDecimal?): CashReconciliation {
            val difference = expected?.let { MoneyPolicy.normalize(declared - it) }
            val outcome = when {
                difference == null -> ReconciliationOutcome.Unavailable
                difference.signum() < 0 -> ReconciliationOutcome.Shortage
                difference.signum() > 0 -> ReconciliationOutcome.Overage
                else -> ReconciliationOutcome.Balanced
            }
            return CashReconciliation(declared, expected, difference, outcome)
        }
    }
}

class ReconciliationPolicy {
    fun calculate(declared: BigDecimal, coverage: AccountingCoverage, postings: List<LedgerPosting>): CashReconciliation {
        require(declared.signum() >= 0)
        val expected = if (coverage == AccountingCoverage.CompleteFromOpening) CashPostingPolicy().expectedCash(postings) else null
        return CashReconciliation.calculated(MoneyPolicy.normalize(declared), expected)
    }
}
