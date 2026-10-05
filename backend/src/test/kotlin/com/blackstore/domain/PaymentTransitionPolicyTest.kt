package com.blackstore.domain

import com.blackstore.application.counter.CounterApplicationService
import com.blackstore.application.sales.LocalSaleCoordinator
import com.blackstore.application.sales.LocalSaleSagaService
import com.blackstore.connector.ReserveScript
import com.blackstore.connector.ScriptedStoreCoreInventoryAdapter
import com.blackstore.domain.cash.StaffRole
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.model.StoreCoreCanonicalContract
import com.blackstore.domain.model.StoreCoreOperationKind
import com.blackstore.domain.model.StoreCoreOperationState
import com.blackstore.domain.port.out.counter.CounterEntryStore
import com.blackstore.domain.port.out.storecore.ReserveLineCommand
import com.blackstore.domain.sales.*
import com.blackstore.infrastructure.counter.InMemoryCounterEntryStore
import com.blackstore.infrastructure.sales.NoOpSaleRecordStore
import com.blackstore.infrastructure.storecore.FixtureCatalogAdapter
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PaymentTransitionPolicyTest {
    private val identity = OperationQuadruple("11111111-1111-1111-1111-111111111111", "terminal-1", "sale-1", "op-1")
    private val line = TicketLine("SKU-1", "Cafe", 1, BigDecimal("18"), BigDecimal.ZERO)
    private val now = Instant.parse("2026-09-23T12:00:00Z")
    private val policy = PaymentTransitionPolicy()

    private class Fixture(val sale: LocalSaleSagaService, val counter: CounterApplicationService, val store: InMemoryCounterEntryStore, val inventory: ScriptedStoreCoreInventoryAdapter)
    private fun fixture(identity: OperationQuadruple = this.identity, decorate: (CounterEntryStore) -> CounterEntryStore = { it }): Fixture {
        val store = InMemoryCounterEntryStore()
        val decorated = decorate(store)
        val guard = LocalSaleCoordinator()
        val inventory = ScriptedStoreCoreInventoryAdapter(ReserveScript.Receipt(ScriptedStoreCoreInventoryAdapter.durable(identity)))
        val sale = LocalSaleSagaService(FixtureCatalogAdapter(StoreCoreCanonicalContract.CANONICAL_PATH, StoreCoreCanonicalContract.VERSION), inventory, inventory,
            NoOpSaleRecordStore(), StoreCoreCanonicalContract.CANONICAL_PATH, StoreCoreCanonicalContract.VERSION, counterEntryStore = decorated, coordinator = guard)
        sale.beginReserve(identity, 1, listOf(ReserveLineCommand("variant-1", 1, "price-v1")), listOf(line), now)
        return Fixture(sale, CounterApplicationService(decorated, sale, guard), store, inventory)
    }

    @Test fun t01UnknownBoundaryTranslatorsAndLedgerDoNotAuthorize() {
        for (raw in listOf(null, "", "future", " captured ", "{}")) {
            assertEquals(PaymentMethod.UNKNOWN, PaymentMethod.fromWire(raw))
            assertEquals(PaymentStatus.UNKNOWN, PaymentStatus.fromWire(raw))
            assertEquals(SaleStatus.UNKNOWN, SaleStatus.fromWire(raw))
        }
        assertEquals(PaymentMethod.CASH, PaymentMethod.fromWire("CASH"))
        assertEquals(PaymentStatus.CAPTURED, PaymentStatus.fromWire("CAPTURED"))
        val f = fixture()
        val sale = f.sale.stored(identity.operationId)!!
        val unknown = OperationLedger.Known(listOf(PaymentLedgerEntry(identity, 1, PaymentMethod.UNKNOWN, PaymentStatus.UNKNOWN, null, null)))
        assertEquals(PaymentCoverage.InvalidUnknown, policy.snapshot(sale, unknown).paymentCoverage)
        assertTrue(policy.capture(sale, unknown, PaymentMethod.CASH, BigDecimal.ONE, BigDecimal.ZERO) is TransitionDecision.Denied)
    }

    @Test fun t03IneligibleSalesNeverSaveCapture() {
        val f = fixture()
        assertThrows<IllegalArgumentException> { f.counter.capture(identity.copy(operationId = "missing"), PaymentMethod.CASH, BigDecimal.ONE, BigDecimal.ZERO) }
        val sale = f.sale.stored(identity.operationId)!!
        for (invalid in listOf(sale.copy(lines = emptyList()), sale.copy(lines = listOf(line.copy(originalUnitPrice = BigDecimal.ZERO))), sale.copy(retired = true), sale.copy(blockSameOperationRepost = true), sale.copy(status = SaleStatus.COMMIT_PENDING))) {
            assertTrue(policy.capture(invalid, OperationLedger.Known(emptyList()), PaymentMethod.CASH, BigDecimal.ONE, BigDecimal.ZERO) is TransitionDecision.Denied)
        }
        assertTrue((f.store.paymentLedger(identity) as OperationLedger.Known).entries.isEmpty())
        f.sale.retire(identity.operationId)
        assertThrows<IllegalArgumentException> { f.counter.capture(identity, PaymentMethod.CASH, BigDecimal.ONE, BigDecimal.ZERO) }
        assertTrue((f.store.paymentLedger(identity) as OperationLedger.Known).entries.isEmpty())
    }

    @Test fun t04ExactSplitAndMoneyNeverRoundOrUseFeesAsCoverage() {
        val f = fixture()
        f.counter.capture(identity, PaymentMethod.CARD, BigDecimal("10.000"), BigDecimal("5.000"))
        assertEquals(PaymentCoverage.Partial, f.sale.paymentSnapshot(f.sale.stored(identity.operationId)!!).paymentCoverage)
        assertThrows<IllegalArgumentException> { f.counter.capture(identity, PaymentMethod.CASH, BigDecimal("9"), BigDecimal.ZERO) }
        for (amount in listOf("0", "-1", "18.001", "0.001", "10.005", "7.995", "1000000000000")) {
            assertThrows<IllegalArgumentException> { f.counter.capture(identity, PaymentMethod.CASH, BigDecimal(amount), BigDecimal.ZERO) }
        }
        assertThrows<IllegalArgumentException> { f.counter.capture(identity, PaymentMethod.CASH, BigDecimal.ONE, BigDecimal("0.001")) }
        assertThrows<IllegalArgumentException> { line.copy(originalUnitPrice = BigDecimal("18.001")) }
        assertTrue(MoneyPolicy.valid(BigDecimal("999999999999.99")))
        f.counter.capture(identity, PaymentMethod.CASH, BigDecimal("8"), BigDecimal.ZERO)
        assertEquals(PaymentCoverage.Paid, f.sale.paymentSnapshot(f.sale.stored(identity.operationId)!!).paymentCoverage)
        assertEquals(BigDecimal("0.00"), f.sale.paymentSnapshot(f.sale.stored(identity.operationId)!!).pendingAmount)
    }

    @Test fun t06OnlyExactCoverageEmitsCommitAndTerminalReplayIsReadOnly() {
        for (amount in listOf("0", "10", "18", "19")) {
            val f = fixture()
            if (amount != "0") f.store.savePayment(PaymentBook().capture(90, PaymentMethod.CASH, BigDecimal(amount), BigDecimal.ZERO), identity)
            if (amount == "18") {
                val committed = f.sale.commit(identity.operationId, now)
                assertEquals(SaleStatus.COMMITTED, committed.status)
                assertEquals(committed, f.sale.commit(identity.operationId, now))
                assertEquals(1, f.inventory.commitAttempts.size)
                assertEquals(1, committed.outbox.count { it.kind == StoreCoreOperationKind.COMMIT })
            } else {
                assertThrows<IllegalArgumentException> { f.sale.commit(identity.operationId, now) }
                assertTrue(f.inventory.commitAttempts.isEmpty())
                assertEquals(1, f.sale.stored(identity.operationId)!!.outbox.size)
            }
        }
    }

    @Test fun t07AndT10IdentityIsolationAndReverseRejectForeignOriginal() {
        val f = fixture()
        val foreign = identity.copy(saleId = "other", deviceId = "terminal-2")
        f.store.savePayment(PaymentBook().capture(95, PaymentMethod.CASH, BigDecimal("18"), BigDecimal.ZERO), foreign)
        assertThrows<IllegalArgumentException> { f.sale.commit(identity.operationId, now) }
        assertThrows<IllegalArgumentException> { f.counter.reverse(identity, 95, 7, StaffRole.CASHIER, "return", "ev") }
        assertTrue((f.store.paymentLedger(identity) as OperationLedger.Known).entries.isEmpty())
        f.inventory.reserveScript = ReserveScript.Receipt(ScriptedStoreCoreInventoryAdapter.durable(foreign))
        f.sale.beginReserve(foreign, 1, listOf(ReserveLineCommand("variant-1", 1, "price-v1")), listOf(line), now)
        assertNull(f.sale.resolveSale(identity.operationId))
        assertThrows<IllegalArgumentException> { f.sale.commit(identity.operationId, now) }
        assertEquals(identity, f.sale.findSale(identity)!!.quadruple)
        val sale = f.sale.findSale(identity)!!
        assertTrue(policy.terminal(sale, OperationLedger.Unknown, StoreCoreOperationKind.COMMIT) is TransitionDecision.Denied)
    }

    @Test fun t08ReleaseRequiresEmptyHistoryIncludingReversalsAndVoids() {
        val empty = fixture()
        assertEquals(SaleStatus.RELEASED, empty.sale.release(identity.operationId).status)
        assertEquals(SaleStatus.RELEASED, empty.sale.release(identity.operationId).status)
        assertEquals(1, empty.inventory.releaseAttempts.size)
        for (status in PaymentStatus.entries) {
            val f = fixture()
            val entry = PaymentLedgerEntry(identity, 90, PaymentMethod.CASH, status, BigDecimal.TEN, BigDecimal.ZERO, if (status == PaymentStatus.REFUNDED || status == PaymentStatus.VOIDED) 89 else null)
            assertTrue(policy.terminal(f.sale.stored(identity.operationId)!!, OperationLedger.Known(listOf(entry)), StoreCoreOperationKind.RELEASE) is TransitionDecision.Denied)
        }
        val f = fixture()
        val captured = f.counter.capture(identity, PaymentMethod.CASH, BigDecimal.TEN, BigDecimal.ZERO)
        f.counter.reverse(identity, captured.id, 7, StaffRole.CASHIER, "return", "evidence")
        assertThrows<IllegalArgumentException> { f.sale.release(identity.operationId) }
        assertThrows<IllegalArgumentException> { f.sale.commit(identity.operationId, now) }
        assertEquals(2, (f.store.paymentLedger(identity) as OperationLedger.Known).entries.size)
    }

    @Test fun t10UnassociatedLegacyEvidenceIsUnknownInsteadOfEmpty() {
        val f = fixture()
        f.store.savePayment(PaymentBook().capture(99, PaymentMethod.CASH, BigDecimal.ONE, BigDecimal.ZERO), identity.operationId)
        assertEquals(OperationLedger.Unknown, f.store.paymentLedger(identity))
        assertThrows<IllegalArgumentException> { f.counter.capture(identity, PaymentMethod.CASH, BigDecimal.ONE, BigDecimal.ZERO) }
        assertThrows<IllegalArgumentException> { f.sale.commit(identity.operationId, now) }
        assertThrows<IllegalArgumentException> { f.sale.release(identity.operationId) }
    }

    @Test fun t09PendingRecoveryPreservesOriginalBodyHashAndOutbox() {
        val f = fixture()
        f.counter.capture(identity, PaymentMethod.CASH, BigDecimal("18"), BigDecimal.ZERO)
        f.inventory.commitScript = ReserveScript.Receipt(ScriptedStoreCoreInventoryAdapter.durable(identity, StoreCoreOperationKind.COMMIT, StoreCoreOperationState.PENDING))
        val pending = f.sale.commit(identity.operationId, now)
        assertEquals(TransitionDecision.RecoverExistingCommand, policy.terminal(pending, OperationLedger.Unknown, StoreCoreOperationKind.COMMIT))
        assertTrue(policy.terminal(pending.copy(outbox = emptyList()), OperationLedger.Unknown, StoreCoreOperationKind.COMMIT) is TransitionDecision.Denied)
        assertTrue(policy.terminal(pending.copy(outbox = pending.outbox.map { it.copy(quadruple = identity.copy(saleId = "other")) }), OperationLedger.Unknown, StoreCoreOperationKind.COMMIT) is TransitionDecision.Denied)
        f.inventory.getReceipt = ScriptedStoreCoreInventoryAdapter.durable(identity, StoreCoreOperationKind.COMMIT, StoreCoreOperationState.COMMITTED)
        val done = f.sale.commit(identity.operationId, now)
        assertEquals(SaleStatus.COMMITTED, done.status)
        assertEquals(pending.outbox, done.outbox)
        assertEquals(1, f.inventory.commitAttempts.size)
        assertEquals(1, f.inventory.getAttempts.size)
        assertThrows<IllegalArgumentException> { f.sale.release(identity.operationId) }
        val conflict = fixture()
        conflict.counter.capture(identity, PaymentMethod.CASH, BigDecimal("18"), BigDecimal.ZERO)
        conflict.inventory.commitScripts += ReserveScript.Fault("CONFLICT", true)
        conflict.inventory.getReceipt = ScriptedStoreCoreInventoryAdapter.durable(identity, reservationRef = "changed-on-get")
        conflict.sale.commit(identity.operationId, now)
        assertEquals(listOf("res-op-1", "res-op-1"), conflict.inventory.commitReservationRefs)
        assertEquals(1, conflict.sale.stored(identity.operationId)!!.outbox.count { it.kind == StoreCoreOperationKind.COMMIT })
    }

    private class BarrierLedger(private val delegate: CounterEntryStore) : CounterEntryStore by delegate {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val armed = AtomicBoolean(false)
        override fun paymentLedger(identity: OperationQuadruple): OperationLedger {
            if (armed.compareAndSet(true, false)) { entered.countDown(); check(proceed.await(10, TimeUnit.SECONDS)) }
            return delegate.paymentLedger(identity)
        }
    }
    private fun race(first: (Fixture) -> Unit, second: (Fixture) -> Unit, prepare: (Fixture) -> Unit = {}): Fixture {
        lateinit var barrier: BarrierLedger
        val f = fixture(decorate = { BarrierLedger(it).also { barrier = it } })
        prepare(f)
        barrier.armed.set(true)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit<Boolean> { first(f); true }
            assertTrue(barrier.entered.await(10, TimeUnit.SECONDS))
            val contender = CountDownLatch(1)
            val b = pool.submit<Boolean> { contender.countDown(); try { second(f); false } catch (expected: IllegalArgumentException) { true } }
            assertTrue(contender.await(10, TimeUnit.SECONDS))
            barrier.proceed.countDown()
            assertTrue(a.get(10, TimeUnit.SECONDS))
            assertTrue(b.get(10, TimeUnit.SECONDS))
        } finally { barrier.proceed.countDown(); pool.shutdownNow() }
        return f
    }

    @Test fun t11CaptureCaptureSerializesReadDecisionAndEffect() {
        val f = race({ it.counter.capture(identity, PaymentMethod.CASH, BigDecimal.TEN, BigDecimal.ZERO) }, { it.counter.capture(identity, PaymentMethod.CASH, BigDecimal.TEN, BigDecimal.ZERO) })
        assertEquals(1, (f.store.paymentLedger(identity) as OperationLedger.Known).entries.size)
        assertEquals(BigDecimal("8.00"), f.sale.paymentSnapshot(f.sale.stored(identity.operationId)!!).pendingAmount)
    }
    @Test fun t11CaptureReleaseBothOrdersUseSharedGuard() {
        val captured = race({ it.counter.capture(identity, PaymentMethod.CASH, BigDecimal.TEN, BigDecimal.ZERO) }, { it.sale.release(identity.operationId) })
        assertTrue(captured.inventory.releaseAttempts.isEmpty())
        val released = race({ it.sale.release(identity.operationId) }, { it.counter.capture(identity, PaymentMethod.CASH, BigDecimal.TEN, BigDecimal.ZERO) })
        assertTrue((released.store.paymentLedger(identity) as OperationLedger.Known).entries.isEmpty())
    }
    @Test fun t11ReverseCommitBothOrdersAndGuardReleaseAfterException() {
        val prepare: (Fixture) -> Unit = { it.counter.capture(identity, PaymentMethod.CASH, BigDecimal("18"), BigDecimal.ZERO) }
        val reversed = race({ it.counter.reverse(identity, 1, 7, StaffRole.CASHIER, "return", "ev") }, { it.sale.commit(identity.operationId, now) }, prepare)
        assertTrue(reversed.inventory.commitAttempts.isEmpty())
        val committed = race({ it.sale.commit(identity.operationId, now) }, { it.counter.reverse(identity, 1, 7, StaffRole.CASHIER, "return", "ev") }, prepare)
        assertEquals(1, (committed.store.paymentLedger(identity) as OperationLedger.Known).entries.size)
        val f = fixture()
        assertThrows<IllegalArgumentException> { f.counter.capture(identity, PaymentMethod.CASH, BigDecimal("19"), BigDecimal.ZERO) }
        f.counter.capture(identity, PaymentMethod.CASH, BigDecimal("18"), BigDecimal.ZERO)
        assertEquals(SaleStatus.COMMITTED, f.sale.commit(identity.operationId, now).status)
    }
}
