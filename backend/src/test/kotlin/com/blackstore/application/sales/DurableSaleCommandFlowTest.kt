package com.blackstore.application.sales

import com.blackstore.domain.model.*
import com.blackstore.domain.port.out.sales.DurableSaleStore
import com.blackstore.domain.port.out.storecore.*
import com.blackstore.domain.sales.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.time.Duration
import java.time.Instant
import java.util.UUID

class DurableSaleCommandFlowTest {
    @Test fun `remote failures translate once and unknown retryable remains GET only`() {
        for (code in com.blackstore.domain.exception.StoreCoreFailureCode.entries) {
            val wire=if(code==com.blackstore.domain.exception.StoreCoreFailureCode.UNKNOWN) "future-provider-error" else code.name
            assertEquals(code,com.blackstore.domain.exception.StoreCoreRemoteFault(wire).failureCode)
        }
        for (retryable in listOf(true,false)) {
            val claim=claim(StoreCoreOperationKind.RESERVE,false)
            val store=org.mockito.Mockito.mock(DurableSaleStore::class.java)
            val inventory=org.mockito.Mockito.mock(StoreCoreInventoryPort::class.java)
            org.mockito.Mockito.`when`(store.claimNext(Duration.ofSeconds(30))).thenReturn(claim)
            org.mockito.Mockito.`when`(inventory.reserve(ReserveInventoryCommand(identity,"historical-catalog",listOf(ReserveLineCommand("historical-variant",2,"historical-price")))))
                .thenThrow(com.blackstore.domain.exception.StoreCoreRemoteFault("future-provider-error",retryable))
            DurableSaleCommandFlow(store,inventory).runNext()
            org.mockito.Mockito.verify(store).deferClaim(claim,if(retryable) RecoveryReason.REMOTE_UNAVAILABLE else RecoveryReason.UNKNOWN,Duration.ofSeconds(1),!retryable)
            org.mockito.Mockito.verify(inventory).reserve(ReserveInventoryCommand(identity,"historical-catalog",listOf(ReserveLineCommand("historical-variant",2,"historical-price"))))
            org.mockito.Mockito.verifyNoMoreInteractions(inventory)
        }
    }
    @Test fun `reconciliation matrix preserves the received receipt instead of discarding it`() {
        for (uncertain in listOf(false,true)) for (state in listOf(StoreCoreOperationState.EXPIRED,StoreCoreOperationState.COMMITTED,StoreCoreOperationState.RELEASED)) {
            val claim=claim(StoreCoreOperationKind.RESERVE,uncertain)
            val store=org.mockito.Mockito.mock(DurableSaleStore::class.java)
            val inventory=org.mockito.Mockito.mock(StoreCoreInventoryPort::class.java)
            val response=receipt(state)
            org.mockito.Mockito.`when`(store.claimNext(Duration.ofSeconds(30))).thenReturn(claim)
            if(uncertain) org.mockito.Mockito.`when`(inventory.getOperation(identity)).thenReturn(response)
            else org.mockito.Mockito.`when`(inventory.reserve(ReserveInventoryCommand(identity,"historical-catalog",listOf(ReserveLineCommand("historical-variant",2,"historical-price"))))).thenReturn(response)
            DurableSaleCommandFlow(store,inventory).runNext()
            org.mockito.Mockito.verify(store).reconcileClaimEvidence(claim,response,if(state==StoreCoreOperationState.EXPIRED) RecoveryReason.EXPIRED else RecoveryReason.TERMINAL_CONTRADICTION)
        }
    }
    private val identity = OperationQuadruple(UUID.randomUUID().toString(), "register", "sale", UUID.randomUUID().toString())
    private val contract = StoreCoreContractRef(StoreCoreCanonicalContract.CANONICAL_PATH, StoreCoreCanonicalContract.VERSION, StoreCoreCanonicalContract.SHA256)
    private fun receipt(state: StoreCoreOperationState, kind: StoreCoreOperationKind = StoreCoreOperationKind.RESERVE) =
        StoreCoreOperationReceipt(identity, kind, state, if (state == StoreCoreOperationState.PENDING) null else "reference",
            if (state == StoreCoreOperationState.PENDING) null else "receipt", contract,
            if (state == StoreCoreOperationState.PENDING) emptyList() else listOf("price-v1"), Instant.now().plusSeconds(120))

    @Test fun `GET matrix is exhaustive and fail closed`() {
        val policy = DurableDeliveryPolicy()
        for (kind in CommandKind.entries) for (state in StoreCoreOperationState.entries) {
            val actual = policy.resolve(kind, receipt(state))
            val expected = when {
                kind == CommandKind.UNKNOWN -> RecoveryDisposition.Unknown
                state == StoreCoreOperationState.PENDING -> RecoveryDisposition.WaitAndGet
                state == StoreCoreOperationState.EXPIRED -> RecoveryDisposition.ReconciliationRequired(RecoveryReason.EXPIRED)
                state == StoreCoreOperationState.RESERVED && kind == CommandKind.RESERVE -> RecoveryDisposition.ApplyEvidence
                state == StoreCoreOperationState.RESERVED -> RecoveryDisposition.RetrySameCommand
                state == StoreCoreOperationState.COMMITTED && kind == CommandKind.COMMIT -> RecoveryDisposition.ApplyEvidence
                state == StoreCoreOperationState.RELEASED && kind == CommandKind.RELEASE -> RecoveryDisposition.ApplyEvidence
                else -> RecoveryDisposition.ReconciliationRequired(RecoveryReason.TERMINAL_CONTRADICTION)
            }
            assertEquals(expected, actual, "$kind / $state")
        }
        assertEquals(RecoveryDisposition.RetrySameCommand, policy.resolve(CommandKind.RESERVE, null))
        for (kind in listOf(CommandKind.COMMIT, CommandKind.RELEASE)) assertEquals(
            RecoveryDisposition.ReconciliationRequired(RecoveryReason.NOT_FOUND_TERMINAL), policy.resolve(kind, null))
    }

    private fun claim(kind: StoreCoreOperationKind, uncertain: Boolean) = ClaimedSaleCommand(1,
        OutboxCommand(identity, kind, contract.canonicalPath, contract.contractVersion, requireNotNull(contract.openapiDigestSha256), "hash",
            reservationRef = if (kind == StoreCoreOperationKind.RESERVE) null else "reference",
            payload = if (kind == StoreCoreOperationKind.RESERVE) CanonicalCommandPayload.Reserve("historical-catalog", listOf(CanonicalReserveLine("historical-variant", 2, "historical-price")))
                else CanonicalCommandPayload.Terminal("reference")), UUID.randomUUID(), 7, uncertain, 1, 9, 10)

    @Test fun `expired lease PENDING performs GET and cannot repost`() {
        val claim = claim(StoreCoreOperationKind.RESERVE, true)
        val store = org.mockito.Mockito.mock(DurableSaleStore::class.java)
        val inventory = org.mockito.Mockito.mock(StoreCoreInventoryPort::class.java)
        org.mockito.Mockito.`when`(store.claimNext(Duration.ofSeconds(30))).thenReturn(claim)
        org.mockito.Mockito.`when`(inventory.getOperation(identity)).thenReturn(receipt(StoreCoreOperationState.PENDING))
        org.mockito.Mockito.`when`(store.findDurable(identity.operationId)).thenReturn(StoredSale(SaleSaga(identity, 10, outbox = listOf(claim.command)), 1, DurableSaleState.PENDING_RESERVATION, 1))
        DurableSaleCommandFlow(store, inventory).runNext()
        org.mockito.Mockito.verify(inventory).getOperation(identity)
        org.mockito.Mockito.verifyNoMoreInteractions(inventory)
        assertEquals(1, org.mockito.Mockito.mockingDetails(store).invocations.count { it.method.name == "applyClaimEvidence" })
    }

    @Test fun `authoritative absence replays original reserve payload`() {
        val claim = claim(StoreCoreOperationKind.RESERVE, true)
        val store = org.mockito.Mockito.mock(DurableSaleStore::class.java)
        val inventory = org.mockito.Mockito.mock(StoreCoreInventoryPort::class.java)
        org.mockito.Mockito.`when`(store.claimNext(Duration.ofSeconds(30))).thenReturn(claim)
        org.mockito.Mockito.`when`(store.findDurable(identity.operationId)).thenReturn(StoredSale(SaleSaga(identity, 10, outbox = listOf(claim.command)), 1, DurableSaleState.PENDING_RESERVATION, 1))
        val exact = ReserveInventoryCommand(identity, "historical-catalog", listOf(ReserveLineCommand("historical-variant", 2, "historical-price")))
        org.mockito.Mockito.`when`(inventory.reserve(exact)).thenReturn(receipt(StoreCoreOperationState.RESERVED))
        DurableSaleCommandFlow(store, inventory).runNext()
        org.mockito.Mockito.verify(inventory).reserve(exact)
    }

    @Test fun `terminal absence never recreates reservation`() {
        for (kind in listOf(StoreCoreOperationKind.COMMIT, StoreCoreOperationKind.RELEASE)) {
            val claim = claim(kind, true)
            val store = org.mockito.Mockito.mock(DurableSaleStore::class.java)
            val inventory = org.mockito.Mockito.mock(StoreCoreInventoryPort::class.java)
            org.mockito.Mockito.`when`(store.claimNext(Duration.ofSeconds(30))).thenReturn(claim)
            DurableSaleCommandFlow(store, inventory).runNext()
            org.mockito.Mockito.verify(store).deferClaim(claim, RecoveryReason.NOT_FOUND_TERMINAL, Duration.ofSeconds(1), true)
            org.mockito.Mockito.verify(inventory).getOperation(identity)
            org.mockito.Mockito.verifyNoMoreInteractions(inventory)
        }
    }

    @Test fun `terminal RESERVED authorizes only the original terminal command and fenced result is delegated`() {
        for (kind in listOf(StoreCoreOperationKind.COMMIT, StoreCoreOperationKind.RELEASE)) {
            val claim = claim(kind, true)
            val store = org.mockito.Mockito.mock(DurableSaleStore::class.java)
            val inventory = org.mockito.Mockito.mock(StoreCoreInventoryPort::class.java)
            val evidence = RemoteEvidence("reference", "receipt", contract.contractVersion, requireNotNull(contract.openapiDigestSha256), listOf("price-v1"), Instant.now().plusSeconds(120))
            val current = SaleSaga(identity, 10, if (kind == StoreCoreOperationKind.COMMIT) SaleStatus.COMMIT_PENDING else SaleStatus.RELEASE_PENDING,
                evidence = evidence, outbox = listOf(claim.command))
            org.mockito.Mockito.`when`(store.claimNext(Duration.ofSeconds(30))).thenReturn(claim)
            org.mockito.Mockito.`when`(store.findDurable(identity.operationId)).thenReturn(StoredSale(current, 1,
                if (kind == StoreCoreOperationKind.COMMIT) DurableSaleState.COMMIT_PENDING else DurableSaleState.RELEASE_PENDING, 1))
            org.mockito.Mockito.`when`(inventory.getOperation(identity)).thenReturn(receipt(StoreCoreOperationState.RESERVED))
            if (kind == StoreCoreOperationKind.COMMIT) org.mockito.Mockito.`when`(inventory.commit(CommitInventoryCommand(identity, "reference")))
                .thenReturn(receipt(StoreCoreOperationState.COMMITTED, kind))
            else org.mockito.Mockito.`when`(inventory.release(ReleaseInventoryCommand(identity, "reference")))
                .thenReturn(receipt(StoreCoreOperationState.RELEASED, kind))
            DurableSaleCommandFlow(store, inventory).runNext()
            val applied = org.mockito.Mockito.mockingDetails(store).invocations.single { it.method.name == "applyClaimEvidence" }
            assertEquals(claim, applied.arguments[0])
            assertEquals(claim.claimToken, (applied.arguments[0] as ClaimedSaleCommand).claimToken)
            assertEquals(claim.claimEpoch, (applied.arguments[0] as ClaimedSaleCommand).claimEpoch)
            org.mockito.Mockito.verify(inventory).getOperation(identity)
            if (kind == StoreCoreOperationKind.COMMIT) org.mockito.Mockito.verify(inventory).commit(CommitInventoryCommand(identity, "reference"))
            else org.mockito.Mockito.verify(inventory).release(ReleaseInventoryCommand(identity, "reference"))
            org.mockito.Mockito.verifyNoMoreInteractions(inventory)
        }
    }
}
