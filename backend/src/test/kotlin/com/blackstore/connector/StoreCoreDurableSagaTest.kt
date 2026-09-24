package com.blackstore.connector

import com.blackstore.application.sales.LocalSaleSagaService
import com.blackstore.domain.exception.ForbiddenOperationException
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.model.StoreCoreCanonicalContract
import com.blackstore.domain.model.StoreCoreOperationKind
import com.blackstore.domain.model.StoreCoreOperationState
import com.blackstore.domain.model.StoreCoreRecoveryAction
import com.blackstore.application.storecore.StoreCoreRecoveryPolicy
import com.blackstore.domain.port.out.storecore.ReconcileQuery
import com.blackstore.domain.port.out.storecore.ReserveLineCommand
import com.blackstore.domain.port.out.storecore.StoreCoreCatalogPort
import com.blackstore.domain.sales.SaleStatus
import com.blackstore.infrastructure.storecore.FixtureCatalogAdapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class StoreCoreDurableSagaTest {

    private val now = Instant.parse("2026-09-23T12:00:00Z")
    private val catalog: StoreCoreCatalogPort =
        FixtureCatalogAdapter(StoreCoreCanonicalContract.CANONICAL_PATH, StoreCoreCanonicalContract.VERSION)

    @Test
    fun intentAndOutboxPrecedeHttpAndCrashKeepsPending() {
        val store = InMemorySaleRecordStore()
        val inventory = ScriptedStoreCoreInventoryAdapter(ReserveScript.Crash())
        val service = service(inventory, store)
        val pending = service.beginReserve(quadruple("op-crash"), 1, listOf(line()), now = now)
        assertEquals(SaleStatus.PENDING_RESERVATION, pending.status)
        assertEquals(listOf("INTENT_OUTBOX"), store.events)
        assertEquals(1, inventory.reserveAttempts.size)
        assertEquals(StoreCoreCanonicalContract.SHA256, pending.outbox.single().openapiDigest)
    }

    @Test
    fun pendingReceiptDoesNotInventEvidence() {
        val store = InMemorySaleRecordStore()
        val q = quadruple("op-pending")
        val inventory =
            ScriptedStoreCoreInventoryAdapter(ReserveScript.Receipt(ScriptedStoreCoreInventoryAdapter.durable(q, state = StoreCoreOperationState.PENDING)))
        val pending = service(inventory, store).beginReserve(q, 1, listOf(line()), now = now)
        assertEquals(SaleStatus.PENDING_RESERVATION, pending.status)
        assertNull(pending.evidence)
        assertEquals(listOf("INTENT_OUTBOX", "INBOX:RESERVE:PENDING"), store.events)
        assertFalse(store.events.contains("RESERVED"))
    }

    @Test
    fun expiredProjectsReconciliationRequired() {
        val store = InMemorySaleRecordStore()
        val q = quadruple("op-expired")
        val inventory =
            ScriptedStoreCoreInventoryAdapter(ReserveScript.Receipt(ScriptedStoreCoreInventoryAdapter.durable(q, state = StoreCoreOperationState.EXPIRED)))
        val saga = service(inventory, store).beginReserve(q, 1, listOf(line()), now = now)
        assertEquals(SaleStatus.RECONCILIATION_REQUIRED, saga.status)
        assertEquals("EXPIRED remote tuple requires reconciliation", saga.reconciliationReason)
        assertEquals("rcpt-op-expired", saga.evidence?.receipt)
        assertTrue(store.events.contains("INTENT_OUTBOX"))
        assertTrue(store.events.indexOf("INTENT_OUTBOX") < store.events.indexOf("RECONCILIATION"))
    }

    @Test
    fun notFoundKeepsPendingAndDoesNotReconcile() {
        val store = InMemorySaleRecordStore()
        val inventory = ScriptedStoreCoreInventoryAdapter(ReserveScript.Fault("NOT_FOUND"))
        val saga = service(inventory, store)
        val pending = saga.beginReserve(quadruple("op-404"), 1, listOf(line()), now = now)
        assertEquals(SaleStatus.PENDING_RESERVATION, pending.status)
        assertEquals(StoreCoreRecoveryAction.NEW_OPERATION_SAME_SALE, StoreCoreRecoveryPolicy.actionForError("NOT_FOUND"))
        assertFalse(store.events.contains("RECONCILIATION"))
        assertFalse(store.events.contains("RESERVED"))
        saga.beginReserve(quadruple("op-404"), 1, listOf(line()), now = now)
        assertEquals(1, inventory.reserveAttempts.size)
    }

    @Test
    fun conflictRecoversWithGetSameQuadruple() {
        val store = InMemorySaleRecordStore()
        val q = quadruple("op-conflict")
        val inventory =
            ScriptedStoreCoreInventoryAdapter(
                ReserveScript.Fault("CONFLICT", retryable = true),
                getReceipt = ScriptedStoreCoreInventoryAdapter.durable(q),
            )
        val reserved = service(inventory, store).beginReserve(q, 1, listOf(line()), now = now)
        assertEquals(SaleStatus.RESERVED, reserved.status)
        assertEquals(listOf("op-conflict"), inventory.getAttempts)
        assertEquals(1, inventory.reserveAttempts.size)
        assertTrue(store.events.contains("RESERVED"))
    }

    @Test
    fun reserveConflictPendingGetSameBodyPostsOnceAndLaterReserveIsNotGetOnly() {
        val store = InMemorySaleRecordStore()
        val q = quadruple("op-reserve-409")
        val inventory =
            ScriptedStoreCoreInventoryAdapter(
                ReserveScript.Fault("CONFLICT", retryable = true),
                getReceipt = ScriptedStoreCoreInventoryAdapter.durable(q, state = StoreCoreOperationState.PENDING),
            )
        inventory.reserveScripts += ReserveScript.Fault("CONFLICT", retryable = true)
        inventory.reserveScripts +=
            ReserveScript.Receipt(ScriptedStoreCoreInventoryAdapter.durable(q, acceptedPriceVersions = listOf("price-v2")))
        val saga = service(inventory, store)
        val first = saga.beginReserve(q, 1, listOf(line()), now = now)
        assertEquals(SaleStatus.RESERVED, first.status)
        assertEquals(listOf("price-v2"), first.evidence?.acceptedPriceVersions)
        assertEquals(2, inventory.reserveAttempts.size)
        assertEquals(1, inventory.getAttempts.size)
        assertFalse(first.recoverWithGet)
        val again = saga.beginReserve(q, 1, listOf(line()), now = now)
        assertEquals(SaleStatus.RESERVED, again.status)
        assertEquals(2, inventory.reserveAttempts.size)
    }

    @Test
    fun mismatchRetainsPendingWithoutMutation() {
        val store = InMemorySaleRecordStore()
        val inventory = ScriptedStoreCoreInventoryAdapter(ReserveScript.Fault("IDEMPOTENCY_PAYLOAD_MISMATCH"))
        val saga = service(inventory, store)
        val pending = saga.beginReserve(quadruple("op-mismatch"), 1, listOf(line()), now = now)
        assertEquals(SaleStatus.PENDING_RESERVATION, pending.status)
        assertNull(pending.evidence)
        assertFalse(store.events.contains("RESERVED"))
        val again = saga.beginReserve(quadruple("op-mismatch"), 1, listOf(line()), now = now)
        assertEquals(SaleStatus.PENDING_RESERVATION, again.status)
        assertEquals(1, inventory.reserveAttempts.size)
    }

    @Test
    fun retiredAndUnknownNeverRepost() {
        val store = InMemorySaleRecordStore()
        val inventory = ScriptedStoreCoreInventoryAdapter(ReserveScript.Fault("OPERATION_RETIRED"))
        val service = service(inventory, store)
        val retired = service.beginReserve(quadruple("op-410"), 1, listOf(line()), now = now)
        assertTrue(retired.retired)
        assertEquals(1, inventory.reserveAttempts.size)
        assertThrows<ForbiddenOperationException> {
            service.beginReserve(quadruple("op-410"), 1, listOf(line()), now = now)
        }
        assertEquals(1, inventory.reserveAttempts.size)
        val projection = inventory.reconcile(ReconcileQuery(listOf("unknown-rcpt"), clientInstanceId = "ci-1"))
        assertFalse(projection.unknownAuthorizesRepost())
    }

    @Test
    fun commitAndReleaseFollowRecoveryMatrix() {
        val q = quadruple("op-commit")
        val storedRef = "res-from-get-op-commit"
        val conflict =
            ScriptedStoreCoreInventoryAdapter(
                ReserveScript.Receipt(ScriptedStoreCoreInventoryAdapter.durable(q)),
                getReceipt = ScriptedStoreCoreInventoryAdapter.durable(q, reservationRef = storedRef),
            )
        conflict.commitScripts += ReserveScript.Fault("CONFLICT", retryable = true)
        val conflictSaga = service(conflict, InMemorySaleRecordStore())
        assertEquals(SaleStatus.RESERVED, conflictSaga.beginReserve(q, 1, listOf(line()), now = now).status)
        val committed = conflictSaga.commit("op-commit", now)
        assertEquals(SaleStatus.COMMITTED, committed.status)
        assertEquals(listOf("res-op-commit", storedRef), conflict.commitReservationRefs)
        assertEquals(2, conflict.commitAttempts.size)
        assertEquals(1, conflict.getAttempts.size)

        val releasedGetQ = quadruple("op-commit-released")
        val releasedGet =
            ScriptedStoreCoreInventoryAdapter(
                ReserveScript.Receipt(ScriptedStoreCoreInventoryAdapter.durable(releasedGetQ)),
                getReceipt =
                    ScriptedStoreCoreInventoryAdapter.durable(
                        releasedGetQ,
                        StoreCoreOperationKind.RELEASE,
                        StoreCoreOperationState.RELEASED,
                        reservationRef = "res-already-released",
                    ),
            )
        releasedGet.commitScripts += ReserveScript.Fault("CONFLICT", retryable = true)
        val releasedGetSaga = service(releasedGet, InMemorySaleRecordStore())
        releasedGetSaga.beginReserve(releasedGetQ, 1, listOf(line()), now = now)
        val alreadyReleased = releasedGetSaga.commit("op-commit-released", now)
        assertEquals(SaleStatus.RELEASED, alreadyReleased.status)
        assertEquals("res-already-released", alreadyReleased.evidence?.reservationRef)
        assertEquals(1, releasedGet.commitAttempts.size)

        val pendingQ = quadruple("op-commit-pending")
        val pendingAdapter =
            ScriptedStoreCoreInventoryAdapter(
                ReserveScript.Receipt(ScriptedStoreCoreInventoryAdapter.durable(pendingQ)),
                commitScript =
                    ReserveScript.Receipt(
                        ScriptedStoreCoreInventoryAdapter.durable(pendingQ, StoreCoreOperationKind.COMMIT, StoreCoreOperationState.PENDING),
                    ),
            )
        val pendingSaga = service(pendingAdapter, InMemorySaleRecordStore())
        pendingSaga.beginReserve(pendingQ, 1, listOf(line()), now = now)
        assertEquals(SaleStatus.COMMIT_PENDING, pendingSaga.commit("op-commit-pending", now).status)
        pendingSaga.commit("op-commit-pending", now)
        assertEquals(1, pendingAdapter.commitAttempts.size)

        val expiredQ = quadruple("op-commit-exp")
        val expired =
            ScriptedStoreCoreInventoryAdapter(
                ReserveScript.Receipt(ScriptedStoreCoreInventoryAdapter.durable(expiredQ)),
                commitScript =
                    ReserveScript.Receipt(
                        ScriptedStoreCoreInventoryAdapter.durable(expiredQ, StoreCoreOperationKind.COMMIT, StoreCoreOperationState.EXPIRED),
                    ),
            )
        val expiredSaga = service(expired, InMemorySaleRecordStore())
        expiredSaga.beginReserve(expiredQ, 1, listOf(line()), now = now)
        val reconciled = expiredSaga.commit("op-commit-exp", now)
        assertEquals(SaleStatus.RECONCILIATION_REQUIRED, reconciled.status)
        assertEquals("EXPIRED remote tuple requires reconciliation", reconciled.reconciliationReason)
        assertEquals("res-op-commit-exp", reconciled.evidence?.reservationRef)
        assertEquals(StoreCoreCanonicalContract.SHA256, reconciled.evidence?.openapiDigest)
        assertEquals(listOf("price-v1"), reconciled.evidence?.acceptedPriceVersions)

        val mismatchQ = quadruple("op-commit-mis")
        val mismatch =
            ScriptedStoreCoreInventoryAdapter(
                ReserveScript.Receipt(ScriptedStoreCoreInventoryAdapter.durable(mismatchQ)),
                commitScript = ReserveScript.Fault("IDEMPOTENCY_PAYLOAD_MISMATCH"),
            )
        val mismatchSaga = service(mismatch, InMemorySaleRecordStore())
        mismatchSaga.beginReserve(mismatchQ, 1, listOf(line()), now = now)
        assertEquals(SaleStatus.COMMIT_PENDING, mismatchSaga.commit("op-commit-mis", now).status)
        mismatchSaga.commit("op-commit-mis", now)
        assertEquals(1, mismatch.commitAttempts.size)

        val tombQ = quadruple("op-commit-410")
        val tomb =
            ScriptedStoreCoreInventoryAdapter(
                ReserveScript.Receipt(ScriptedStoreCoreInventoryAdapter.durable(tombQ)),
                commitScript = ReserveScript.Fault("OPERATION_RETIRED"),
            )
        val tombSaga = service(tomb, InMemorySaleRecordStore())
        tombSaga.beginReserve(tombQ, 1, listOf(line()), now = now)
        assertTrue(tombSaga.commit("op-commit-410", now).retired)
        assertThrows<ForbiddenOperationException> { tombSaga.commit("op-commit-410", now) }
        assertEquals(1, tomb.commitAttempts.size)

        val relConflictQ = quadruple("op-rel-409")
        val relConflict =
            ScriptedStoreCoreInventoryAdapter(
                ReserveScript.Receipt(ScriptedStoreCoreInventoryAdapter.durable(relConflictQ)),
                getReceipt = ScriptedStoreCoreInventoryAdapter.durable(relConflictQ),
            )
        relConflict.releaseScripts += ReserveScript.Fault("CONFLICT", retryable = true)
        val relConflictSaga = service(relConflict, InMemorySaleRecordStore())
        relConflictSaga.beginReserve(relConflictQ, 1, listOf(line()), now = now)
        assertEquals(SaleStatus.RELEASED, relConflictSaga.release("op-rel-409").status)
        assertEquals(2, relConflict.releaseAttempts.size)

        val committedGetQ = quadruple("op-rel-committed")
        val committedGet =
            ScriptedStoreCoreInventoryAdapter(
                ReserveScript.Receipt(ScriptedStoreCoreInventoryAdapter.durable(committedGetQ)),
                getReceipt =
                    ScriptedStoreCoreInventoryAdapter.durable(
                        committedGetQ,
                        StoreCoreOperationKind.COMMIT,
                        StoreCoreOperationState.COMMITTED,
                        reservationRef = "res-already-committed",
                    ),
            )
        committedGet.releaseScripts += ReserveScript.Fault("CONFLICT", retryable = true)
        val committedGetSaga = service(committedGet, InMemorySaleRecordStore())
        committedGetSaga.beginReserve(committedGetQ, 1, listOf(line()), now = now)
        val alreadyCommitted = committedGetSaga.release("op-rel-committed")
        assertEquals(SaleStatus.COMMITTED, alreadyCommitted.status)
        assertEquals("res-already-committed", alreadyCommitted.evidence?.reservationRef)
        assertEquals(1, committedGet.releaseAttempts.size)

        val relPendingQ = quadruple("op-rel-pending")
        val relPending =
            ScriptedStoreCoreInventoryAdapter(
                ReserveScript.Receipt(ScriptedStoreCoreInventoryAdapter.durable(relPendingQ)),
                releaseScript =
                    ReserveScript.Receipt(
                        ScriptedStoreCoreInventoryAdapter.durable(relPendingQ, StoreCoreOperationKind.RELEASE, StoreCoreOperationState.PENDING),
                    ),
            )
        val relPendingSaga = service(relPending, InMemorySaleRecordStore())
        relPendingSaga.beginReserve(relPendingQ, 1, listOf(line()), now = now)
        assertEquals(SaleStatus.RELEASE_PENDING, relPendingSaga.release("op-rel-pending").status)
        relPendingSaga.release("op-rel-pending")
        assertEquals(1, relPending.releaseAttempts.size)

        val relMisQ = quadruple("op-rel-mis")
        val relMis =
            ScriptedStoreCoreInventoryAdapter(
                ReserveScript.Receipt(ScriptedStoreCoreInventoryAdapter.durable(relMisQ)),
                releaseScript = ReserveScript.Fault("IDEMPOTENCY_PAYLOAD_MISMATCH"),
            )
        val relMisSaga = service(relMis, InMemorySaleRecordStore())
        relMisSaga.beginReserve(relMisQ, 1, listOf(line()), now = now)
        assertEquals(SaleStatus.RELEASE_PENDING, relMisSaga.release("op-rel-mis").status)
        relMisSaga.release("op-rel-mis")
        assertEquals(1, relMis.releaseAttempts.size)

        val relTombQ = quadruple("op-rel-410")
        val relTomb =
            ScriptedStoreCoreInventoryAdapter(
                ReserveScript.Receipt(ScriptedStoreCoreInventoryAdapter.durable(relTombQ)),
                releaseScript = ReserveScript.Fault("OPERATION_RETIRED"),
            )
        val relTombSaga = service(relTomb, InMemorySaleRecordStore())
        relTombSaga.beginReserve(relTombQ, 1, listOf(line()), now = now)
        assertTrue(relTombSaga.release("op-rel-410").retired)
        assertThrows<ForbiddenOperationException> { relTombSaga.release("op-rel-410") }
        assertEquals(1, relTomb.releaseAttempts.size)

        val releaseQ = quadruple("op-rel-exp")
        val release =
            ScriptedStoreCoreInventoryAdapter(
                ReserveScript.Receipt(ScriptedStoreCoreInventoryAdapter.durable(releaseQ)),
                releaseScript =
                    ReserveScript.Receipt(
                        ScriptedStoreCoreInventoryAdapter.durable(releaseQ, StoreCoreOperationKind.RELEASE, StoreCoreOperationState.EXPIRED),
                    ),
            )
        val releaseSaga = service(release, InMemorySaleRecordStore())
        releaseSaga.beginReserve(releaseQ, 1, listOf(line()), now = now)
        val releasedExpired = releaseSaga.release("op-rel-exp")
        assertEquals(SaleStatus.RECONCILIATION_REQUIRED, releasedExpired.status)
        assertEquals("res-op-rel-exp", releasedExpired.evidence?.reservationRef)
        assertEquals(listOf("price-v1"), releasedExpired.evidence?.acceptedPriceVersions)
        assertEquals(1, release.releaseAttempts.size)
    }

    private fun service(
        inventory: ScriptedStoreCoreInventoryAdapter,
        store: InMemorySaleRecordStore,
    ) = LocalSaleSagaService(
        catalogPort = catalog,
        inventoryPort = inventory,
        retirementPort = inventory,
        saleRecordStore = store,
        canonicalPath = StoreCoreCanonicalContract.CANONICAL_PATH,
        contractVersion = StoreCoreCanonicalContract.VERSION,
    )

    private fun quadruple(operationId: String) =
        OperationQuadruple("11111111-1111-1111-1111-111111111111", "terminal-1", "sale-1", operationId)

    private fun line() = ReserveLineCommand("variant-1", 1, "price-v1")
}
