package com.blackstore.domain

import com.blackstore.domain.accounting.*
import com.blackstore.domain.model.*
import com.blackstore.domain.sales.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class CashCloseDomainTest {
    private val identity = OperationQuadruple("client", "device", "sale", "operation")
    private val reservation = RemoteEvidence("reservation", "reserve-receipt", "v1", "digest", listOf("price-v1"), null)
    private val command = OutboxCommand(identity, StoreCoreOperationKind.RELEASE, "/blackstore-integration/v1", "v1", "digest", "hash",
        reservationRef = reservation.reservationRef, payload = CanonicalCommandPayload.Terminal(reservation.reservationRef))
    private val receipt = StoreCoreOperationReceipt(identity, StoreCoreOperationKind.RELEASE, StoreCoreOperationState.RELEASED,
        reservation.reservationRef, "release-receipt", StoreCoreContractRef(command.canonicalPath, "v1", "digest"), listOf("price-v1"), null)
    private val sale = SaleSaga(identity, 1, SaleStatus.RELEASED, reservation, outbox = listOf(command))

    @Test fun `terminal receipt differs from reservation receipt and must match the original command`() {
        val policy = CashCloseTerminalityPolicy()
        assertEquals(SaleTerminality.Proven, policy.assess(sale, command, receipt, 0))
        assertEquals(SaleTerminality.Pending, policy.assess(sale, command, receipt, 1))
        assertEquals(SaleTerminality.Pending, policy.assess(sale.copy(status = SaleStatus.RELEASE_PENDING), command, receipt, 0))
        assertEquals(SaleTerminality.Unknown, policy.assess(sale, command.copy(payload = null), receipt, 0))
        assertEquals(SaleTerminality.Unknown, policy.assess(sale, command, receipt.copy(quadruple = identity.copy(saleId = "foreign")), 0))
        assertEquals(SaleTerminality.Unknown, policy.assess(sale, command, receipt.copy(acceptedPriceVersions = listOf("other")), 0))
        assertEquals(SaleTerminality.Unknown, policy.assess(sale.copy(status = SaleStatus.UNKNOWN), command, receipt, 0))
        assertEquals(SaleTerminality.Unknown, policy.assess(sale, command, null, 0))
    }

    @Test fun `snapshot invariants preserve legacy nulls and prohibit incorrect signed outcomes`() {
        val legacy = CashCloseSnapshot(BigDecimal.TEN, null, null, ReconciliationOutcome.Unavailable,
            AccountingCoverage.LegacyIncomplete, Instant.EPOCH, 0)
        assertNull(legacy.expectedCash)
        assertThrows(IllegalArgumentException::class.java) { legacy.copy(expectedCash = BigDecimal.ZERO) }
        val complete = CashCloseSnapshot(BigDecimal("8.00"), BigDecimal("10.00"), BigDecimal("-2.00"),
            ReconciliationOutcome.Shortage, AccountingCoverage.CompleteFromOpening, Instant.EPOCH, 1)
        assertThrows(IllegalArgumentException::class.java) { complete.copy(outcome = ReconciliationOutcome.Overage) }
        assertThrows(IllegalArgumentException::class.java) { complete.copy(difference = BigDecimal.ZERO) }
        assertThrows(IllegalArgumentException::class.java) { complete.copy(coverage = AccountingCoverage.Unknown) }
    }
}
