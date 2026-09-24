package com.blackstore.connector

import com.blackstore.application.port.out.storecore.StoreCoreContractFacade
import com.blackstore.application.storecore.StoreCoreEnvelopeValidator
import com.blackstore.application.storecore.StoreCoreRecoveryPolicy
import com.blackstore.domain.exception.BlockedStoreCoreIntegrationException
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.model.StoreCoreCanonicalContract
import com.blackstore.domain.model.StoreCoreContractRef
import com.blackstore.domain.model.StoreCoreOperationKind
import com.blackstore.domain.model.StoreCoreOperationReceipt
import com.blackstore.domain.model.StoreCoreOperationState
import com.blackstore.domain.model.StoreCoreRecoveryAction
import com.blackstore.domain.port.out.storecore.CatalogSnapshot
import com.blackstore.domain.port.out.storecore.ReconcileQuery
import com.blackstore.domain.port.out.storecore.ReserveInventoryCommand
import com.blackstore.domain.port.out.storecore.StoreCoreCatalogPort
import com.blackstore.infrastructure.storecore.BlockedStoreCoreInventoryAdapter
import com.blackstore.infrastructure.storecore.FixtureStoreCoreInventoryAdapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class StoreCoreRecoveryPolicyTest {

    private val quadruple =
        OperationQuadruple(
            clientInstanceId = "ci-1",
            deviceId = "dev-1",
            saleId = "sale-1",
            operationId = "op-1",
        )

    private val contract =
        StoreCoreContractRef(
            StoreCoreCanonicalContract.CANONICAL_PATH,
            StoreCoreCanonicalContract.VERSION,
            StoreCoreCanonicalContract.SHA256,
        )

    @Test
    fun pendingRetainsWithoutInventedReceipt() {
        val receipt = receipt(StoreCoreOperationState.PENDING, receipt = null, reservationRef = null, versions = emptyList())
        assertEquals(StoreCoreRecoveryAction.RETAIN_PENDING, StoreCoreRecoveryPolicy.actionFor(receipt))
        val evidence = StoreCoreRecoveryPolicy.evidenceFrom(receipt)
        assertNull(evidence.receipt)
        assertNull(evidence.reservationRef)
        assertFalse(evidence.authorizesRepost())
    }

    @Test
    fun expiredProjectsReconciliationRequiredWithCompleteTuple() {
        val receipt = receipt(StoreCoreOperationState.EXPIRED)
        assertEquals(StoreCoreRecoveryAction.RECORD_RECONCILIATION_REQUIRED, StoreCoreRecoveryPolicy.actionFor(receipt))
        val evidence = StoreCoreRecoveryPolicy.evidenceFrom(receipt)
        assertEquals("rcpt-1", evidence.receipt)
        assertEquals("res-1", evidence.reservationRef)
        assertEquals(listOf("price-v1"), evidence.acceptedPriceVersions)
        assertEquals("EXPIRED remote tuple requires reconciliation", evidence.reconciliationReason)
        assertFalse(evidence.authorizesRepost())
    }

    @Test
    fun retiredAndUnknownNeverRepost() {
        assertEquals(StoreCoreRecoveryAction.NEVER_REPOST, StoreCoreRecoveryPolicy.actionForError("OPERATION_RETIRED"))
        assertEquals(StoreCoreRecoveryAction.GET_SAME_QUADRUPLE, StoreCoreRecoveryPolicy.actionForError("CONFLICT"))
        assertEquals(StoreCoreRecoveryAction.NEW_OPERATION_SAME_SALE, StoreCoreRecoveryPolicy.actionForError("INSUFFICIENT_STOCK"))
        assertEquals(StoreCoreRecoveryAction.NEW_OPERATION_SAME_SALE, StoreCoreRecoveryPolicy.actionForError("NOT_FOUND"))
        assertEquals(StoreCoreRecoveryAction.RETAIN_DURABLE_EVIDENCE, StoreCoreRecoveryPolicy.actionForError("IDEMPOTENCY_PAYLOAD_MISMATCH"))
        assertEquals(StoreCoreRecoveryAction.NEVER_REPOST, StoreCoreRecoveryPolicy.actionForError("UNKNOWN_CODE"))
        assertFalse(StoreCoreRecoveryPolicy.actionForError("IDEMPOTENCY_PAYLOAD_MISMATCH") == StoreCoreRecoveryAction.GET_SAME_QUADRUPLE)
        assertFalse(StoreCoreRecoveryPolicy.actionForError("UNKNOWN_CODE") == StoreCoreRecoveryAction.GET_SAME_QUADRUPLE)
    }

    @Test
    fun durableReceiptRejectsMissingDigest() {
        assertThrows<IllegalArgumentException> {
            receipt(StoreCoreOperationState.EXPIRED, digest = null)
        }
        assertThrows<IllegalArgumentException> {
            receipt(StoreCoreOperationState.RESERVED, digest = "")
        }
    }

    @Test
    fun facadeReconcileUnknownDoesNotAuthorizeRepost() {
        val inventory = FixtureStoreCoreInventoryAdapter(StoreCoreEnvelopeValidator(), contract.canonicalPath, contract.contractVersion)
        inventory.reserve(
            ReserveInventoryCommand(
                quadruple = quadruple,
                catalogVersion = "v1",
                lines = emptyList(),
            ),
        )
        val facade =
            StoreCoreContractFacade(
                catalogPort = EmptyCatalog(),
                inventoryPort = inventory,
                reconcilePort = inventory,
            )
        val projection = facade.reconcile(ReconcileQuery(listOf("rcpt-${quadruple.operationId}", "unknown-rcpt")))
        assertEquals(1, projection.present.size)
        assertEquals(listOf("unknown-rcpt"), projection.unknownReceipts)
        assertFalse(projection.unknownAuthorizesRepost())
    }

    @Test
    fun blockedReconcileFailsClosed() {
        val blocked = BlockedStoreCoreInventoryAdapter()
        assertThrows<BlockedStoreCoreIntegrationException> {
            blocked.reconcile(ReconcileQuery(listOf("rcpt-1")))
        }
    }

    private fun receipt(
        state: StoreCoreOperationState,
        receipt: String? = "rcpt-1",
        reservationRef: String? = "res-1",
        versions: List<String> = listOf("price-v1"),
        digest: String? = StoreCoreCanonicalContract.SHA256,
    ) = StoreCoreOperationReceipt(
        quadruple = quadruple,
        kind = StoreCoreOperationKind.RESERVE,
        state = state,
        reservationRef = reservationRef,
        receipt = receipt,
        contract =
            StoreCoreContractRef(
                StoreCoreCanonicalContract.CANONICAL_PATH,
                StoreCoreCanonicalContract.VERSION,
                digest,
            ),
        acceptedPriceVersions = versions,
        expiresAt = Instant.parse("2026-09-23T00:00:00Z"),
    )

    private class EmptyCatalog : StoreCoreCatalogPort {
        override fun currentSnapshot(): CatalogSnapshot? = null
    }
}
