package com.blackstore.domain

import com.blackstore.domain.accounting.LedgerEventKind
import com.blackstore.domain.reports.*
import com.blackstore.domain.sales.PaymentMethod
import com.blackstore.presentation.controller.AccountingReportRequestTranslator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.*

class AccountingReportReadTest {
    @Test fun dstUsesLocalMidnightsAndHalfOpenBounds() {
        val zone = ZoneId.of("America/New_York")
        val effective = EffectiveReportZone(zone, "test-v1", Instant.EPOCH)
        val resolver = ReportPeriodResolver()
        val spring = resolver.day(ReportPeriod.Day(LocalDate.of(2026, 3, 8), zone), effective)
        val fall = resolver.day(ReportPeriod.Day(LocalDate.of(2026, 11, 1), zone), effective)
        assertEquals(23, Duration.between(spring.start, spring.endExclusive).toHours())
        assertEquals(25, Duration.between(fall.start, fall.endExclusive).toHours())
        assertTrue(spring.start in spring)
        assertFalse(spring.endExclusive in spring)
    }
    @Test fun paidFlowsExcludeOpeningAccrualAndAdjustments() {
        val movements = listOf(ReportMovement(LedgerEventKind.OPENING, PaymentMethod.CASH, BigDecimal("100.00")),
            ReportMovement(LedgerEventKind.PAYMENT, PaymentMethod.CASH, BigDecimal("20.00")),
            ReportMovement(LedgerEventKind.PAYMENT, PaymentMethod.CARD, BigDecimal("30.00")),
            ReportMovement(LedgerEventKind.REFUND, PaymentMethod.CASH, BigDecimal("-4.00")),
            ReportMovement(LedgerEventKind.EXPENSE_ACCRUAL, PaymentMethod.CASH, BigDecimal("9.00")))
        val totals = FinancialTotalsPolicy().totals(movements)
        assertEquals(BigDecimal("16.00"), totals.getValue(PaymentMethod.CASH).operatingCashFlow)
        assertEquals(BigDecimal("30.00"), totals.getValue(PaymentMethod.CARD).collected)
    }
    @Test fun strictEdgeRejectsIgnoredFiltersAndUnknownValues() {
        assertThrows(IllegalArgumentException::class.java) { AccountingReportRequestTranslator.shift(mapOf("cashSessionId" to "1", "cashierId" to "3")) }
        assertThrows(IllegalArgumentException::class.java) { AccountingReportRequestTranslator.day(mapOf("localDate" to "2026-10-07", "zone" to "UTC", "formula" to "bogus")) }
        assertEquals(ReportPeriod.Shift(1), AccountingReportRequestTranslator.shift(mapOf("cashSessionId" to "1")).period)
    }
    @Test fun mixedLegacyUniverseCannotClaimCompleteOrSyntheticZero() {
        val bounds = ReportBounds(Instant.EPOCH, Instant.EPOCH.plusSeconds(100))
        val result = ReportCoveragePolicy().assess(ReportSourceCoverage(2, 1, 1, 0), bounds, Instant.EPOCH)
        assertEquals(DataCompleteness.Partial, result.state)
        assertTrue(CompletenessCause.LegacyActivity in result.causes)
        assertEquals(DataCompleteness.Complete, ReportCoveragePolicy().assess(ReportSourceCoverage(0, 0, 0, 0), bounds, Instant.EPOCH).state)
        assertEquals(DataCompleteness.Unavailable, ReportCoveragePolicy().assess(null, bounds, Instant.EPOCH).state)
    }
    @Test fun provisionalExpectedCashRequiresCompleteOpeningAndPreservesSigns() {
        val policy = ReportExpectedCashPolicy()
        val facts = listOf(ReportMovement(LedgerEventKind.OPENING, PaymentMethod.CASH, BigDecimal("5.00")),
            ReportMovement(LedgerEventKind.PAYMENT, PaymentMethod.CASH, BigDecimal("10.00")),
            ReportMovement(LedgerEventKind.EXPENSE_PAID, PaymentMethod.CASH, BigDecimal("-3.00")))
        assertEquals(BigDecimal("12.00"), policy.expected(facts, listOf(MetricCompleteness.Complete)))
        assertNull(policy.expected(facts, listOf(MetricCompleteness(DataCompleteness.LegacyIncomplete, setOf(CompletenessCause.LegacyActivity)))))
        assertNull(policy.expected(facts.drop(1), listOf(MetricCompleteness.Complete)))
    }
}
