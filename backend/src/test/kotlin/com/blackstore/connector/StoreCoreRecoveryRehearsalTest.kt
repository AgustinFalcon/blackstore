package com.blackstore.connector

import com.blackstore.application.sales.LocalSaleSagaService
import com.blackstore.application.storecore.StoreCoreControlPlane
import com.blackstore.application.storecore.StoreCoreDispatchGuard
import com.blackstore.domain.exception.BlockedStoreCoreIntegrationException
import com.blackstore.domain.exception.ForbiddenOperationException
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.model.StoreCoreCanonicalContract
import com.blackstore.domain.model.StoreCoreOperationState
import com.blackstore.domain.port.out.storecore.ReconcileQuery
import com.blackstore.domain.port.out.storecore.ReserveLineCommand
import com.blackstore.domain.sales.SaleStatus
import com.blackstore.infrastructure.storecore.FixtureCatalogAdapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class StoreCoreRecoveryRehearsalTest {

    private val now = Instant.parse("2026-09-23T18:00:00Z")
    private val catalog = FixtureCatalogAdapter(StoreCoreCanonicalContract.CANONICAL_PATH, StoreCoreCanonicalContract.VERSION)

    @Test
    fun rehearsalCoversTimeoutPendingExpiredConflictTombstoneAndRollback() {
        val store = InMemorySaleRecordStore()
        val crash = ScriptedStoreCoreInventoryAdapter(ReserveScript.Crash())
        val crashed = saga(crash, store).beginReserve(q("op-timeout"), 1, lines(), now = now)
        assertEquals(SaleStatus.PENDING_RESERVATION, crashed.status)
        assertEquals("INTENT_OUTBOX", store.events.first())

        val pendingStore = InMemorySaleRecordStore()
        val pendingAdapter =
            ScriptedStoreCoreInventoryAdapter(
                ReserveScript.Receipt(ScriptedStoreCoreInventoryAdapter.durable(q("op-pending"), state = StoreCoreOperationState.PENDING)),
            )
        val pending = saga(pendingAdapter, pendingStore).beginReserve(q("op-pending"), 1, lines(), now = now)
        assertNull(pending.evidence)

        val expiredStore = InMemorySaleRecordStore()
        val expiredAdapter =
            ScriptedStoreCoreInventoryAdapter(
                ReserveScript.Receipt(ScriptedStoreCoreInventoryAdapter.durable(q("op-expired"), state = StoreCoreOperationState.EXPIRED)),
            )
        val expired = saga(expiredAdapter, expiredStore).beginReserve(q("op-expired"), 1, lines(), now = now)
        assertEquals(SaleStatus.RECONCILIATION_REQUIRED, expired.status)
        assertTrue(!expired.reconciliationReason.isNullOrBlank())
        assertEquals("rcpt-op-expired", expired.evidence?.receipt)

        val conflictStore = InMemorySaleRecordStore()
        val conflictAdapter =
            ScriptedStoreCoreInventoryAdapter(
                ReserveScript.Fault("CONFLICT", retryable = true),
                getReceipt = ScriptedStoreCoreInventoryAdapter.durable(q("op-conflict"), state = StoreCoreOperationState.PENDING),
            )
        conflictAdapter.reserveScripts += ReserveScript.Fault("CONFLICT", retryable = true)
        conflictAdapter.reserveScripts +=
            ReserveScript.Receipt(ScriptedStoreCoreInventoryAdapter.durable(q("op-conflict")))
        val conflict = saga(conflictAdapter, conflictStore).beginReserve(q("op-conflict"), 1, lines(), now = now)
        assertEquals(SaleStatus.RESERVED, conflict.status)
        assertEquals(2, conflictAdapter.reserveAttempts.size)
        assertEquals(1, conflictAdapter.getAttempts.size)
        assertFalse(conflict.recoverWithGet)

        val tombStore = InMemorySaleRecordStore()
        val tomb = ScriptedStoreCoreInventoryAdapter(ReserveScript.Fault("OPERATION_RETIRED"))
        val service = saga(tomb, tombStore)
        val retired = service.beginReserve(q("op-410"), 1, lines(), now = now)
        assertTrue(retired.retired)
        assertThrows<ForbiddenOperationException> { service.beginReserve(q("op-410"), 1, lines(), now = now) }
        assertEquals(1, tomb.reserveAttempts.size)
        assertFalse(tomb.reconcile(ReconcileQuery(listOf("unknown-rcpt"), clientInstanceId = "ci-1")).unknownAuthorizesRepost())

        val plane = StoreCoreControlPlane(capabilityActive = true, killSwitch = false, identityRef = "id-ref", tokenRef = "tok-ref")
        plane.rotate("id-ref-rotated", "tok-ref-rotated", "actor-owner", "rotate after evidence")
        plane.rollback("actor-owner", "rollback preserves local evidence")
        assertEquals(SaleStatus.RECONCILIATION_REQUIRED, expired.status)
        assertThrows<BlockedStoreCoreIntegrationException> { plane.assertLocalDispatchAllowed() }
        assertThrows<BlockedStoreCoreIntegrationException> {
            StoreCoreDispatchGuard.assertCanDispatch(plane.transportSettings(conflictSettings()))
        }
    }

    private fun saga(inventory: ScriptedStoreCoreInventoryAdapter, store: InMemorySaleRecordStore) =
        LocalSaleSagaService(
            catalogPort = catalog,
            inventoryPort = inventory,
            retirementPort = inventory,
            saleRecordStore = store,
            canonicalPath = StoreCoreCanonicalContract.CANONICAL_PATH,
            contractVersion = StoreCoreCanonicalContract.VERSION,
        )

    private fun q(operationId: String) =
        OperationQuadruple("11111111-1111-1111-1111-111111111111", "terminal-1", "sale-1", operationId)

    private fun lines() = listOf(ReserveLineCommand("variant-1", 1, "price-v1"))

    private fun conflictSettings() =
        com.blackstore.application.storecore.StoreCoreTransportSettings(
            enabled = true,
            killSwitch = false,
            capabilityActive = true,
            baseUrl = "http://127.0.0.1:1",
            tlsRequired = false,
            allowPlainLoopback = true,
            identityRef = "id-ref",
            tokenRef = "tok-ref",
            timeoutMs = 2000,
            maxRetries = 2,
            path = StoreCoreCanonicalContract.CANONICAL_PATH,
            version = StoreCoreCanonicalContract.VERSION,
            digest = StoreCoreCanonicalContract.SHA256,
        )
}
