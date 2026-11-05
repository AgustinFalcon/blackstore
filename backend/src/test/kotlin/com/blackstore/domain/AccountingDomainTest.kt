package com.blackstore.domain

import com.blackstore.domain.accounting.*
import com.blackstore.domain.model.*
import com.blackstore.domain.reports.*
import com.blackstore.domain.sales.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class AccountingDomainTest {
    private fun money(value: String) = BigDecimal(value)
    private val at = Instant.parse("2026-10-07T12:00:00Z")
    private val operationId = UUID.fromString("10000000-0000-0000-0000-000000000001")

    @Test fun `wire vocabularies reject unfamiliar or absent values`() {
        assertEquals(AccountingRuntimeState.Unknown, AccountingRuntimeState.fromWire(null))
        assertEquals(AccountingRuntimeState.PreActivation, AccountingRuntimeState.fromWire(AccountingRuntimeState.PreActivation.wire))
        assertEquals(AccountingContractVersion.Unknown, AccountingContractVersion.fromWire(null))
        assertEquals(AccountingCommandKind.Unknown, AccountingCommandKind.fromWire(null))
        assertEquals(AccountingCommandFailure.Unknown, AccountingCommandFailure.fromWire(null))
        assertEquals(AccountingCommandFailure.LegacyContractDisabled, AccountingCommandFailure.fromWire("LEGACY_CONTRACT_DISABLED"))
        assertEquals(LedgerEventKind.Unknown, LedgerEventKind.fromWire("REVERSAL"))
        assertEquals(LedgerComponent.Unknown, LedgerComponent.fromWire("CLOSING_ADJUSTMENT"))
        assertEquals(LedgerOriginKind.Unknown, LedgerOriginKind.fromWire("raw"))
        assertEquals(AccountingCoverage.Unknown, AccountingCoverage.fromWire(null))
        assertEquals(ReconciliationOutcome.Unknown, ReconciliationOutcome.fromWire("raw"))
        assertEquals(ReportPeriodKind.Unknown, ReportPeriodKind.fromWire("raw"))
        assertEquals(FormulaKind.Contribution, FormulaKind.fromWire("CONTRIBUTION"))
        assertEquals(FormulaKind.Unknown, FormulaKind.fromWire("raw"))
        assertEquals(FormulaVersion.Unknown, FormulaVersion.fromWire("raw"))
        assertEquals(DataCompleteness.LegacyIncomplete, DataCompleteness.fromWire("LEGACY_INCOMPLETE"))
        assertEquals(DataCompleteness.Unknown, DataCompleteness.fromWire("raw"))
        assertEquals(CompletenessCause.Unknown, CompletenessCause.fromWire("raw"))
        assertEquals(AccountingMetric.Unknown, AccountingMetric.fromWire(null))
        LedgerEventKind.entries.forEach { assertEquals(it, LedgerEventKind.fromWire(it.name)) }
        LedgerComponent.entries.forEach { assertEquals(it, LedgerComponent.fromWire(it.name)) }
        LedgerOriginKind.entries.forEach { assertEquals(it, LedgerOriginKind.fromWire(it.name)) }
    }

    @Test fun `activation is irreversible and pause never enables legacy writes`() {
        val policy = AccountingRuntimePolicy()
        val pre = AccountingLifecycle(AccountingRuntimeState.PreActivation, null)
        val readiness = AccountingActivationReadiness(true, true, true, true, true)
        assertThrows(IllegalArgumentException::class.java) { pre.activate(at, readiness.copy(clientsV2Verified = false)) }
        val active = pre.activate(at, readiness)
        val paused = active.pause()
        assertEquals(at, paused.activatedAt)
        assertEquals(active, paused.resume())
        assertThrows(IllegalArgumentException::class.java) { active.activate(at, readiness) }
        assertThrows(IllegalArgumentException::class.java) { AccountingLifecycle(AccountingRuntimeState.PreActivation, at) }
        assertEquals(AccountingMutationAdmission.Allowed, policy.admit(pre.state, AccountingContractVersion.V1))
        assertEquals(AccountingMutationAdmission.AccountingNotActivated, policy.admit(pre.state, AccountingContractVersion.V2))
        assertEquals(AccountingMutationAdmission.LegacyContractDisabled, policy.admit(active.state, AccountingContractVersion.V1))
        assertEquals(AccountingMutationAdmission.LegacyContractDisabled, policy.admit(paused.state, AccountingContractVersion.V1))
        assertEquals(AccountingMutationAdmission.AccountingPaused, policy.admit(paused.state, AccountingContractVersion.V2))
        assertEquals(AccountingMutationAdmission.Unknown, policy.admit(AccountingRuntimeState.Unknown, AccountingContractVersion.V2))
    }

    @Test fun `only actual cash movements affect expected cash and fees need separate payment`() {
        val cash = PaymentRecord(1, PaymentMethod.CASH, money("100"), money("5"), PaymentStatus.CAPTURED)
        val card = PaymentRecord(2, PaymentMethod.CARD, money("60"), money("3"), PaymentStatus.CAPTURED)
        val refund = PaymentBook().reverse(cash, 3, 1, "cancelled", "receipt-3")
        val evidence = AccountingEvidence(2, "paid provider fee", "fee receipt")
        val postings = listOf(
            CashPostingPolicy().opening(1, money("25")),
            PaymentPostingPolicy().capture(cash), PaymentPostingPolicy().capture(card),
            PaymentPostingPolicy().refund(cash, refund, 2),
            PaymentPostingPolicy().paidFee(operationId, PaymentMethod.CASH, money("2"), evidence),
            LedgerPosting(LedgerEventKind.EXPENSE_ACCRUAL, LedgerComponent.EXPENSE_ACCRUAL, PaymentMethod.CASH, money("50"), LedgerOrigin(LedgerOriginKind.EXPENSE, 1)),
            LedgerPosting(LedgerEventKind.EXPENSE_PAID, LedgerComponent.EXPENSE_SETTLEMENT, PaymentMethod.CASH, money("-3"), LedgerOrigin(LedgerOriginKind.EXPENSE, 1)),
        )
        assertEquals(money("20.00"), CashPostingPolicy().expectedCash(postings))
        assertEquals(money("55.00"), postings.fold(money("0.00")) { sum, posting -> sum + posting.operatingFlowDelta })
        for (method in listOf(PaymentMethod.CARD, PaymentMethod.TRANSFER, PaymentMethod.OTHER)) {
            assertEquals(money("0.00"), PaymentPostingPolicy().capture(cash.copy(method = method)).cashDelta)
        }
    }

    @Test fun `invalid signs unknown types precision and partial payment refund fail closed`() {
        assertEquals(money("0.00"), CashPostingPolicy().opening(1, money("0")).amount)
        assertThrows(IllegalArgumentException::class.java) { CashPostingPolicy().opening(1, money("-1")) }
        assertThrows(IllegalArgumentException::class.java) { CashPostingPolicy().opening(1, money("0.001")) }
        assertThrows(IllegalArgumentException::class.java) { CashPostingPolicy().opening(1, money("1000000000000")) }
        assertThrows(IllegalArgumentException::class.java) { LedgerPosting(LedgerEventKind.Unknown, LedgerComponent.Unknown, PaymentMethod.CASH, money("1"), LedgerOrigin(LedgerOriginKind.OPERATION, operationId)) }
        assertThrows(IllegalArgumentException::class.java) { LedgerPosting(LedgerEventKind.PAYMENT, LedgerComponent.PAYMENT_CAPTURE, PaymentMethod.CASH, money("-1"), LedgerOrigin(LedgerOriginKind.PAYMENT, 1)) }
        assertThrows(IllegalArgumentException::class.java) { LedgerPosting(LedgerEventKind.ADJUSTMENT, LedgerComponent.ADJUSTMENT, PaymentMethod.CASH, money("1"), LedgerOrigin(LedgerOriginKind.OPERATION, operationId)) }
        val payment = PaymentRecord(1, PaymentMethod.CASH, money("100"), money("0"), PaymentStatus.CAPTURED)
        val partial = PaymentBook().reverse(payment, 2, 1, "cancelled", "proof").copy(amount = money("50"))
        assertThrows(IllegalArgumentException::class.java) { PaymentPostingPolicy().refund(payment, partial, 1) }
    }

    @Test fun `reconciliation preserves legacy nulls and signed difference`() {
        val policy = ReconciliationPolicy()
        val postings = listOf(CashPostingPolicy().opening(1, money("100")))
        assertEquals(ReconciliationOutcome.Balanced, policy.calculate(money("100"), AccountingCoverage.CompleteFromOpening, postings).outcome)
        val shortage = policy.calculate(money("90"), AccountingCoverage.CompleteFromOpening, postings)
        assertEquals(ReconciliationOutcome.Shortage, shortage.outcome)
        assertEquals(money("-10.00"), shortage.difference)
        assertEquals(ReconciliationOutcome.Overage, policy.calculate(money("110"), AccountingCoverage.CompleteFromOpening, postings).outcome)
        for (coverage in listOf(AccountingCoverage.LegacyIncomplete, AccountingCoverage.Unknown)) {
            val legacy = policy.calculate(money("90"), coverage, postings)
            assertEquals(ReconciliationOutcome.Unavailable, legacy.outcome)
            assertNull(legacy.expectedCash)
            assertNull(legacy.difference)
            assertEquals(money("90.00"), legacy.declaredCash)
        }
        assertThrows(IllegalArgumentException::class.java) { policy.calculate(money("100"), AccountingCoverage.CompleteFromOpening, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { policy.calculate(money("100"), AccountingCoverage.CompleteFromOpening, postings + postings) }
    }

    @Test fun `DAY resolves DST dates as local days and uses half open bounds`() {
        val zone = ZoneId.of("America/New_York")
        val version = EffectiveReportZone(zone, "zone-v1", Instant.parse("2020-01-01T00:00:00Z"))
        val resolver = ReportPeriodResolver()
        val spring = resolver.day(ReportPeriod.Day(LocalDate.of(2026, 3, 8), zone), version)
        val autumn = resolver.day(ReportPeriod.Day(LocalDate.of(2026, 11, 1), zone), version)
        assertEquals(23L, Duration.between(spring.start, spring.endExclusive).toHours())
        assertEquals(25L, Duration.between(autumn.start, autumn.endExclusive).toHours())
        assertTrue(spring.start in spring)
        assertFalse(spring.endExclusive in spring)
        assertFalse(spring.intersects(spring.start.minusSeconds(10), spring.start))
        assertTrue(spring.intersects(spring.start.minusSeconds(10), spring.start.plusSeconds(1)))
        assertThrows(IllegalArgumentException::class.java) { resolver.day(ReportPeriod.Day(LocalDate.of(2026, 3, 8), zone), version.copy(effectiveAt = spring.start.plusSeconds(1))) }
        assertThrows(IllegalArgumentException::class.java) { resolver.shift(ReportPeriod.Shift(1), 2, at, null, at.plusSeconds(100)) }
        assertEquals(at.plusSeconds(50), resolver.shift(ReportPeriod.Shift(1), 1, at, at.plusSeconds(50), at.plusSeconds(100)).endExclusive)
    }

    @Test fun `coverage universe catches legacy with no postings and zero needs absence evidence`() {
        val policy = ReportCoveragePolicy()
        val bounds = ReportBounds(at, at.plusSeconds(100))
        assertEquals(DataCompleteness.Unavailable, policy.assess(null, bounds, at).state)
        assertEquals(DataCompleteness.Complete, policy.assess(ReportSourceCoverage(0, 0, 0, 0), bounds, at).state)
        val mixed = policy.assess(ReportSourceCoverage(2, 1, 1, 0), bounds, at)
        assertEquals(DataCompleteness.Partial, mixed.state)
        assertTrue(CompletenessCause.LegacyActivity in mixed.causes)
        assertEquals(DataCompleteness.LegacyIncomplete, policy.assess(ReportSourceCoverage(1, 0, 1, 0), bounds, at).state)
        assertTrue(CompletenessCause.BeforeActivation in policy.assess(ReportSourceCoverage(0, 0, 0, 0), bounds, at.plusSeconds(1)).causes)
        assertThrows(IllegalArgumentException::class.java) { ReportSourceCoverage(1, 1, 1, 0) }
    }

    @Test fun `contribution does not deduct refunds twice and unapproved formulas stay unavailable`() {
        val inputs = AccountingFormulaInputs(money("100"), money("5"), money("20"), MetricCompleteness.Complete)
        val formula = ReportFormula()
        assertEquals(money("75.00"), formula.evaluate(FormulaKind.Contribution, FormulaVersion.V1, inputs).value)
        for (kind in listOf(FormulaKind.ReinvestmentSuggestion, FormulaKind.OwnerSurplusEstimate, FormulaKind.Unknown)) {
            val result = formula.evaluate(kind, FormulaVersion.V1, inputs)
            assertNull(result.value)
            assertEquals(DataCompleteness.Unavailable, result.completeness.state)
        }
        assertNull(formula.evaluate(FormulaKind.Contribution, FormulaVersion.Unknown, inputs).value)
        val legacy = inputs.copy(completeness = MetricCompleteness(DataCompleteness.LegacyIncomplete, setOf(CompletenessCause.LegacyActivity)))
        assertNull(formula.evaluate(FormulaKind.Contribution, FormulaVersion.V1, legacy).value)
    }

    @Test fun `terminality and recognition require durable matching evidence without pending commands`() {
        val identity = OperationQuadruple("client", "device", "sale", "operation")
        val evidence = RemoteEvidence("reservation", "receipt", "v1", "digest", listOf("price-v1"), null)
        val receipt = StoreCoreOperationReceipt(identity, StoreCoreOperationKind.COMMIT, StoreCoreOperationState.COMMITTED, evidence.reservationRef, evidence.receipt, StoreCoreContractRef("/blackstore-integration/v1", "v1", "digest"), listOf("price-v1"), null)
        val sale = SaleSaga(identity, 1, SaleStatus.COMMITTED, evidence, lines = listOf(TicketLine("sku", "Item", 2, money("100"), money("10"))))
        val terminality = AccountingSaleTerminalityPolicy()
        assertEquals(SaleTerminality.Proven, terminality.assess(sale, receipt, 0))
        assertEquals(SaleTerminality.Pending, terminality.assess(sale, receipt, 1))
        assertEquals(SaleTerminality.Unknown, terminality.assess(sale, null, 0))
        assertEquals(SaleTerminality.Unknown, terminality.assess(sale, receipt.copy(receipt = "other"), 0))
        val recognition = CommercialRecognitionPolicy().recognize(sale, receipt, 0, at)
        assertEquals(identity, recognition.identity)
        assertEquals(money("200.00"), recognition.grossSales)
        assertEquals(money("20.00"), recognition.discounts)
        assertEquals(money("180.00"), recognition.netSales)
        val released = sale.copy(status = SaleStatus.RELEASED)
        assertThrows(IllegalArgumentException::class.java) { CommercialRecognitionPolicy().recognize(released, receipt, 0, at) }
        assertEquals(SaleTerminality.Pending, terminality.assess(sale.copy(status = SaleStatus.RELEASE_PENDING), receipt, 0))
    }
}
