package com.blackstore.domain

import com.blackstore.application.sales.LocalSaleSagaService
import com.blackstore.application.storecore.StoreCoreEnvelope
import com.blackstore.application.storecore.StoreCoreEnvelopeValidator
import com.blackstore.domain.cash.CashSessionBook
import com.blackstore.domain.cash.RoleAuthorizationPolicy
import com.blackstore.domain.cash.SessionAction
import com.blackstore.domain.cash.StaffRole
import com.blackstore.domain.catalog.CatalogSalePolicy
import com.blackstore.domain.companion.CompanionEnvironment
import com.blackstore.domain.compliance.FiscalAuthorization
import com.blackstore.domain.compliance.FiscalAuthorizationKind
import com.blackstore.domain.compliance.FiscalBoundaryPolicy
import com.blackstore.domain.exception.ForbiddenOperationException
import com.blackstore.domain.ledger.AppendOnlyLedger
import com.blackstore.domain.ledger.CashEventType
import com.blackstore.domain.ledger.CashLedgerEvent
import com.blackstore.domain.ledger.InboxDeduplicator
import com.blackstore.domain.ledger.InboxKey
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.model.StoreCoreContractRef
import com.blackstore.domain.port.out.storecore.CatalogSnapshot
import com.blackstore.domain.port.out.storecore.ReserveLineCommand
import com.blackstore.domain.reports.CostFact
import com.blackstore.domain.reports.ReportFormulas
import com.blackstore.domain.reports.ShiftFigures
import com.blackstore.domain.sales.FiscalStatus
import com.blackstore.domain.sales.PaymentBook
import com.blackstore.domain.sales.SaleSaga
import com.blackstore.domain.sales.PaymentMethod
import com.blackstore.domain.sales.PaymentStatus
import com.blackstore.domain.sales.SaleStatus
import com.blackstore.domain.sales.TicketLine
import com.blackstore.infrastructure.sales.NoOpSaleRecordStore
import com.blackstore.infrastructure.storecore.FixtureCatalogAdapter
import com.blackstore.infrastructure.storecore.FixtureStoreCoreInventoryAdapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Instant

class AutonomousCoreTest {

    private val now = Instant.parse("2026-09-22T12:00:00Z")
    private val roles = RoleAuthorizationPolicy()
    private val cash = CashSessionBook()

    @Test
    fun rolesFollowSessionOwnership() {
        roles.assertAllowed(StaffRole.CASHIER, SessionAction.OPERATE, actorId = 7, sessionCashierId = 7)
        assertThrows<ForbiddenOperationException> {
            roles.assertAllowed(StaffRole.CASHIER, SessionAction.OPERATE, actorId = 7, sessionCashierId = 8)
        }
        roles.assertAllowed(StaffRole.SUPERVISOR, SessionAction.OVERRIDE, actorId = 2, sessionCashierId = 7)
        roles.assertAllowed(StaffRole.OWNER, SessionAction.CONFIGURE, actorId = 1)
        roles.assertAllowed(StaffRole.AUDITOR, SessionAction.READ_AUDIT, actorId = 9)
        assertThrows<ForbiddenOperationException> {
            roles.assertAllowed(StaffRole.AUDITOR, SessionAction.OPERATE, actorId = 9, sessionCashierId = 9)
        }
        assertThrows<ForbiddenOperationException> {
            roles.assertAllowed(StaffRole.CASHIER, SessionAction.VIEW_REPORTS, actorId = 7)
        }
    }

    @Test
    fun onlyOneOpenSessionPerTerminalAndClosureIsAudited() {
        val open = cash.open(emptyList(), id = 1, terminalId = 10, cashierId = 7, openingCash = BigDecimal.ZERO, openedAt = now)
        assertThrows<ForbiddenOperationException> {
            cash.open(listOf(open), id = 2, terminalId = 10, cashierId = 8, openingCash = BigDecimal.TEN, openedAt = now)
        }
        val (closed, audit) = cash.close(open, declared = BigDecimal("150.00"), closedAt = now.plusSeconds(60), actorId = 7, reason = "shift end")
        assertEquals(com.blackstore.domain.cash.CashSessionStatus.CLOSED, closed.status)
        assertEquals("CASH_SESSION_CLOSED", audit.eventType)
        assertEquals(open.openingCash, closed.openingCash)
    }

    @Test
    fun staleCatalogBlocksSaleAndStaysReadable() {
        val policy = CatalogSalePolicy()
        val fresh = snapshot(stale = false)
        policy.assertSaleAllowed(fresh, now)
        val stale = fresh.copy(stale = true)
        assertEquals("fixture-v1", stale.version)
        assertThrows<ForbiddenOperationException> { policy.assertSaleAllowed(stale, now) }
        assertThrows<ForbiddenOperationException> { policy.assertSaleAllowed(null, now) }
    }

    @Test
    fun sagaPersistsOutboxBeforeReserveAndDoesNotRepostRetiredOperation() {
        val ledger = com.blackstore.infrastructure.counter.InMemoryCounterEntryStore()
        val ticket = listOf(TicketLine("SKU-1", "Cafe", 1, BigDecimal("18"), BigDecimal.ZERO))
        val catalog = FixtureCatalogAdapter("/blackstore-integration/v1", "1.0.0-draft")
        val inventory = FixtureStoreCoreInventoryAdapter(StoreCoreEnvelopeValidator(), "/blackstore-integration/v1", "1.0.0-draft")
        val service =
            LocalSaleSagaService(
                catalogPort = catalog,
                inventoryPort = inventory,
                retirementPort = inventory,
                saleRecordStore = NoOpSaleRecordStore(),
                canonicalPath = "/blackstore-integration/v1",
                contractVersion = "1.0.0-draft",
                counterEntryStore = ledger,
            )
        inventory.crashBeforeReceipt = true
        val pending = service.beginReserve(quadruple("op-1"), cashSessionId = 1, lines = listOf(line()), ticketLines = ticket, now = now)
        assertEquals(SaleStatus.PENDING_RESERVATION, pending.status)
        assertEquals(1, pending.outbox.size)
        assertNull(pending.evidence)
        assertEquals(1, inventory.reserveAttempts.size)

        val reserved = service.beginReserve(quadruple("op-1"), cashSessionId = 1, lines = listOf(line()), now = now)
        assertEquals(SaleStatus.RESERVED, reserved.status)
        assertEquals("rcpt-op-1", reserved.evidence?.receipt)
        assertEquals(listOf("price-demo-1"), reserved.evidence?.acceptedPriceVersions)

        val replay = service.beginReserve(quadruple("op-1"), cashSessionId = 1, lines = listOf(line()), now = now)
        assertEquals(reserved, replay)
        assertEquals(2, inventory.reserveAttempts.size)

        service.retire("op-retired")
        assertThrows<ForbiddenOperationException> {
            service.beginReserve(quadruple("op-retired"), cashSessionId = 1, lines = listOf(line()), now = now)
        }
        assertEquals(listOf("op-1", "op-1"), inventory.reserveAttempts)

        com.blackstore.application.counter.CounterApplicationService(ledger, service, com.blackstore.application.sales.LocalSaleCoordinator.local)
            .capture(quadruple("op-1"), PaymentMethod.CASH, BigDecimal("18"), BigDecimal.ZERO)
        val committed = service.commit("op-1", now)
        assertEquals(SaleStatus.COMMITTED, committed.status)
        assertEquals(1, committed.outbox.count { it.kind == com.blackstore.domain.model.StoreCoreOperationKind.COMMIT })
        assertEquals(committed, service.commit("op-1", now))
        assertEquals(1, inventory.commitAttempts.size)

        val reservedRelease = service.beginReserve(quadruple("op-release"), cashSessionId = 1, lines = listOf(line()), ticketLines = ticket, now = now)
        assertEquals(SaleStatus.RESERVED, reservedRelease.status)
        val released = service.release("op-release")
        assertEquals(SaleStatus.RELEASED, released.status)
        assertEquals(1, inventory.releaseAttempts.size)

        val productionLedger = com.blackstore.infrastructure.counter.InMemoryCounterEntryStore()
        val production =
            LocalSaleSagaService(
                catalogPort = catalog,
                inventoryPort = inventory,
                retirementPort = inventory,
                saleRecordStore = NoOpSaleRecordStore(),
                canonicalPath = "/blackstore-integration/v1",
                contractVersion = "1.0.0-draft",
                environmentName = "PRODUCTION",
                counterEntryStore = productionLedger,
            )
        production.beginReserve(quadruple("op-prod"), cashSessionId = 1, lines = listOf(line()), ticketLines = ticket, now = now)
        com.blackstore.application.counter.CounterApplicationService(productionLedger, production, com.blackstore.application.sales.LocalSaleCoordinator.local)
            .capture(quadruple("op-prod"), PaymentMethod.CASH, BigDecimal("18"), BigDecimal.ZERO)
        assertThrows<ForbiddenOperationException> { production.commit("op-prod", now) }
        assertEquals(1, inventory.commitAttempts.size)
    }

    @Test
    fun envelopeRejectsAmbiguousSuccessAndRetryableTombstone() {
        val validator = StoreCoreEnvelopeValidator()
        assertThrows<ForbiddenOperationException> {
            validator.requireSuccess(
                StoreCoreEnvelope(
                    code = 200,
                    data = "ok",
                    errorCode = "CONFLICT",
                    retryable = null,
                    message = null,
                    traceId = "t",
                ),
            )
        }
        assertThrows<ForbiddenOperationException> {
            validator.requireError(
                StoreCoreEnvelope<String>(
                    code = 410,
                    data = null,
                    errorCode = "OPERATION_RETIRED",
                    retryable = true,
                    message = "retired",
                    traceId = "t",
                ),
            )
        }
    }

    @Test
    fun splitPaymentReversalDoesNotMutateOriginal() {
        val line = TicketLine("SKU-1", "Cafe", 2, BigDecimal("10.00"), BigDecimal("1.00"))
        assertEquals(BigDecimal("9.00"), line.effectiveUnitPrice)
        val book = PaymentBook()
        val captured = book.capture(1, PaymentMethod.CARD, BigDecimal("18.00"), BigDecimal("0.50"))
        val reversal = book.reverse(captured, reversalId = 2, actorId = 7, reason = "customer return", evidenceRef = "ev-1")
        assertEquals(PaymentStatus.CAPTURED, captured.status)
        assertEquals(PaymentStatus.REFUNDED, reversal.status)
        assertEquals(1L, reversal.originalPaymentId)
    }

    @Test
    fun ledgerAndInboxAreAppendOnlyAndIdempotent() {
        val ledger = AppendOnlyLedger()
        val opening =
            CashLedgerEvent(1, 10, CashEventType.OPENING, BigDecimal.ZERO, actorId = 7)
        val once = ledger.append(emptyList(), opening)
        assertThrows<ForbiddenOperationException> { ledger.append(once, opening.copy(id = 2)) }
        assertThrows<IllegalArgumentException> {
            CashLedgerEvent(3, 10, CashEventType.PAYMENT, BigDecimal.ZERO, actorId = 7)
        }
        assertThrows<ForbiddenOperationException> { ledger.rejectMutation() }
        val inbox = InboxDeduplicator()
        val key = InboxKey("ci", "dev", "sale", "op", "RESERVE", "a".repeat(64))
        val first = inbox.accept(emptySet(), key)
        assertTrue(inbox.isDuplicate(first, key))
        assertEquals(first, inbox.accept(first, key))
    }

    @Test
    fun reportsKeepMarginUnknownAndProjectionsNonFiscal() {
        val figures =
            ShiftFigures(
                grossSales = BigDecimal("100.00"),
                discounts = BigDecimal("10.00"),
                refunds = BigDecimal("5.00"),
                collected = BigDecimal("90.00"),
                feesPaid = BigDecimal("2.00"),
                expensesPaid = BigDecimal("8.00"),
                costs = listOf(CostFact(null, null, null, false)),
            )
        assertEquals(BigDecimal("90.00"), figures.netSales)
        assertEquals(BigDecimal("75.00"), figures.operatingCashFlow)
        assertNull(figures.margin)
        val projection = ReportFormulas().contribution(figures, version = "v1")
        assertFalse(projection.fiscalResult)
        assertFalse(projection.freeCash)
        assertEquals("CONTRIBUTION", projection.formulaName)
    }

    @Test
    fun reconciliationRequiresReasonAndRemoteEvidenceIsAtomic() {
        assertThrows<IllegalArgumentException> {
            SaleSaga(
                quadruple = quadruple("op-r"),
                cashSessionId = 1,
                status = SaleStatus.RECONCILIATION_REQUIRED,
                reconciliationReason = " ",
            )
        }
        val reconciled =
            SaleSaga(
                quadruple = quadruple("op-r"),
                cashSessionId = 1,
                status = SaleStatus.RECONCILIATION_REQUIRED,
                reconciliationReason = "receipt missing",
            )
        assertNull(reconciled.evidence)
    }

    @Test
    fun productionCommitRequiresFiscalAuthorizationAndDoesNotEmit() {
        val policy = FiscalBoundaryPolicy()
        policy.assertCanCommit(CompanionEnvironment.TEST, FiscalStatus.NOT_CONFIGURED, authorization = null, now = now)
        assertThrows<ForbiddenOperationException> {
            policy.assertCanCommit(CompanionEnvironment.PRODUCTION, FiscalStatus.NOT_CONFIGURED, authorization = null, now = now)
        }
        val authorization =
            FiscalAuthorization(
                kind = FiscalAuthorizationKind.LAWFUL_EXCEPTION,
                responsibleApprovalRef = "owner-signed",
                accountantApprovalRef = "accountant-signed",
                validFrom = now.minusSeconds(60),
                validUntil = now.plusSeconds(3600),
            )
        policy.assertCanCommit(CompanionEnvironment.PRODUCTION, FiscalStatus.PENDING, authorization, now)
    }

    private fun snapshot(stale: Boolean) =
        CatalogSnapshot(
            version = "fixture-v1",
            importedAt = now.minusSeconds(30),
            validUntil = now.plusSeconds(3600),
            contract = StoreCoreContractRef("/blackstore-integration/v1", "1.0.0-draft"),
            stale = stale,
        )

    private fun quadruple(operationId: String) =
        OperationQuadruple("11111111-1111-1111-1111-111111111111", "terminal-1", "sale-1", operationId)

    private fun line() = ReserveLineCommand("variant-1", 1, "price-v1")
}
