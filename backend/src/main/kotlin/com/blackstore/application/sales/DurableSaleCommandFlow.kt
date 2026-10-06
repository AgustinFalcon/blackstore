package com.blackstore.application.sales

import com.blackstore.domain.exception.StoreCoreRemoteFault
import com.blackstore.domain.exception.StoreCoreFailureCode
import com.blackstore.domain.model.*
import com.blackstore.domain.port.out.sales.DurableSaleStore
import com.blackstore.domain.port.out.storecore.*
import com.blackstore.domain.sales.*
import java.time.Duration
import java.security.MessageDigest

/** The recovery matrix is independent of HTTP, persistence and scheduling. */
class DurableDeliveryPolicy {
    fun resolve(kind: CommandKind, receipt: StoreCoreOperationReceipt?): RecoveryDisposition {
        if (kind == CommandKind.UNKNOWN) return RecoveryDisposition.Unknown
        if (receipt == null) return if (kind == CommandKind.RESERVE) RecoveryDisposition.RetrySameCommand
            else RecoveryDisposition.ReconciliationRequired(RecoveryReason.NOT_FOUND_TERMINAL)
        return when (receipt.state) {
            StoreCoreOperationState.PENDING -> RecoveryDisposition.WaitAndGet
            StoreCoreOperationState.EXPIRED -> RecoveryDisposition.ReconciliationRequired(RecoveryReason.EXPIRED)
            StoreCoreOperationState.RESERVED -> if (kind == CommandKind.RESERVE) RecoveryDisposition.ApplyEvidence else RecoveryDisposition.RetrySameCommand
            StoreCoreOperationState.COMMITTED -> if (kind == CommandKind.COMMIT) RecoveryDisposition.ApplyEvidence
                else RecoveryDisposition.ReconciliationRequired(RecoveryReason.TERMINAL_CONTRADICTION)
            StoreCoreOperationState.RELEASED -> if (kind == CommandKind.RELEASE) RecoveryDisposition.ApplyEvidence
                else RecoveryDisposition.ReconciliationRequired(RecoveryReason.TERMINAL_CONTRADICTION)
        }
    }
}

class ClaimPendingCommand(private val store: DurableSaleStore, private val lease: Duration) {
    fun next() = store.claimNext(lease)
    fun operation(id: String, kind: CommandKind) = store.claimCommand(id, kind, lease)
}

class DispatchStoredCommand(private val inventory: StoreCoreInventoryPort) {
    fun execute(claim: ClaimedSaleCommand): StoreCoreOperationReceipt {
        val command = claim.command
        return when (command.kind) {
            StoreCoreOperationKind.RESERVE -> {
                val payload = command.payload as? CanonicalCommandPayload.Reserve ?: error("incomplete reserve command")
                inventory.reserve(ReserveInventoryCommand(command.quadruple, payload.catalogVersion,
                    payload.lines.map { ReserveLineCommand(it.variantId, it.quantity, it.expectedPriceVersion) }))
            }
            StoreCoreOperationKind.COMMIT -> {
                val payload = command.payload as? CanonicalCommandPayload.Terminal ?: error("incomplete commit command")
                inventory.commit(CommitInventoryCommand(command.quadruple, payload.reservationRef))
            }
            StoreCoreOperationKind.RELEASE -> {
                val payload = command.payload as? CanonicalCommandPayload.Terminal ?: error("incomplete release command")
                inventory.release(ReleaseInventoryCommand(command.quadruple, payload.reservationRef))
            }
        }
    }
}

class ResolveUncertainDelivery(private val inventory: StoreCoreInventoryPort, private val policy: DurableDeliveryPolicy) {
    fun execute(claim: ClaimedSaleCommand): Pair<RecoveryDisposition, StoreCoreOperationReceipt?> {
        val receipt = inventory.getOperation(claim.command.quadruple)
        if (receipt != null && !compatible(claim, receipt)) return RecoveryDisposition.ReconciliationRequired(RecoveryReason.CONTRACT_INCOMPATIBLE) to receipt
        return policy.resolve(CommandKind.valueOf(claim.command.kind.name), receipt) to receipt
    }
}

private fun compatible(claim: ClaimedSaleCommand, receipt: StoreCoreOperationReceipt): Boolean {
    val command = claim.command
    return receipt.quadruple == command.quadruple && receipt.contract.canonicalPath == command.canonicalPath &&
        receipt.contract.contractVersion == command.contractVersion &&
        (receipt.state == StoreCoreOperationState.PENDING || receipt.contract.openapiDigestSha256 == command.openapiDigest) &&
        (command.kind == StoreCoreOperationKind.RESERVE || receipt.state == StoreCoreOperationState.PENDING ||
            receipt.reservationRef == (command.payload as? CanonicalCommandPayload.Terminal)?.reservationRef)
}

class ApplyRemoteEvidence(private val store: DurableSaleStore) {
    fun execute(claim: ClaimedSaleCommand, receipt: StoreCoreOperationReceipt): AttemptOutcome {
        val command = claim.command
        require(compatible(claim, receipt))
        val current = store.findDurable(command.quadruple.operationId)?.saga ?: error("sale missing")
        val saga = if (receipt.state == StoreCoreOperationState.PENDING) current else current.applyRemoteDurable(receipt.state,
            RemoteEvidence(requireNotNull(receipt.reservationRef), requireNotNull(receipt.receipt), receipt.contract.contractVersion,
                requireNotNull(receipt.contract.openapiDigestSha256), receipt.acceptedPriceVersions, receipt.expiresAt))
        val material = listOf(receipt.kind.name, receipt.state.name, receipt.receipt, receipt.reservationRef,
            receipt.acceptedPriceVersions.joinToString(","), receipt.expiresAt?.toString()).joinToString("|")
        val hash = MessageDigest.getInstance("SHA-256").digest(material.toByteArray()).joinToString("") { "%02x".format(it) }
        return store.applyClaimEvidence(claim, saga, hash, receipt.state.name)
    }
}

/** Orders single-responsibility steps. All HTTP runs after admission/claim transactions return. */
class DurableSaleCommandFlow(private val store: DurableSaleStore, inventory: StoreCoreInventoryPort,
    lease: Duration = Duration.ofSeconds(30), private val backoff: Duration = Duration.ofSeconds(1), private val maxAttempts: Int = 8) {
    private val claim = ClaimPendingCommand(store, lease)
    private val policy = DurableDeliveryPolicy()
    private val resolve = ResolveUncertainDelivery(inventory, policy)
    private val dispatch = DispatchStoredCommand(inventory)
    private val apply = ApplyRemoteEvidence(store)
    init { require(lease.seconds in 1..300 && backoff.seconds in 1..3600 && maxAttempts in 1..100) }
    fun runNext(): Boolean { val pending = claim.next() ?: return false; execute(pending); return true }
    fun runOperation(id: String, kind: CommandKind) { claim.operation(id, kind)?.let(::execute) }
    private fun defer(claim: ClaimedSaleCommand, reason: RecoveryReason, reconcile: Boolean = false) {
        store.deferClaim(claim, reason, backoff, reconcile)
    }
    private fun execute(claim: ClaimedSaleCommand) {
        if (claim.attempts > maxAttempts) { defer(claim, RecoveryReason.ATTEMPTS_EXHAUSTED, true); return }
        val command = claim.command
        if (command.payload == null) { defer(claim, RecoveryReason.INCOMPLETE_EVIDENCE, true); return }
        try {
            StoreCoreCanonicalContract.assertCompatible(command.canonicalPath, command.contractVersion, command.openapiDigest)
        } catch (_: IllegalStateException) { defer(claim, RecoveryReason.CONTRACT_INCOMPATIBLE, true); return }
        // DB failures are intentionally not caught: they abort, and the lease drives later GET recovery.
        val result = try {
            if (claim.uncertain) resolve.execute(claim) else RecoveryDisposition.RetrySameCommand to null
        } catch (fault: StoreCoreRemoteFault) { remoteFault(claim, fault); return }
        catch (_: IllegalStateException) { defer(claim, RecoveryReason.REMOTE_UNAVAILABLE); return }
        catch (_: java.io.IOException) { defer(claim, RecoveryReason.REMOTE_UNAVAILABLE); return }
        catch (_: IllegalArgumentException) { defer(claim, RecoveryReason.UNKNOWN, true); return }
        when (val disposition = result.first) {
            RecoveryDisposition.ApplyEvidence -> apply.execute(claim, requireNotNull(result.second))
            RecoveryDisposition.WaitAndGet -> {
                result.second?.let { apply.execute(claim, it) } ?: defer(claim, RecoveryReason.REMOTE_PENDING)
            }
            RecoveryDisposition.RetrySameCommand -> {
                val receipt = try { dispatch.execute(claim) }
                catch (fault: StoreCoreRemoteFault) { remoteFault(claim, fault); return }
                catch (_: IllegalStateException) { defer(claim, RecoveryReason.REMOTE_UNAVAILABLE); return }
                catch (_: java.io.IOException) { defer(claim, RecoveryReason.REMOTE_UNAVAILABLE); return }
                catch (_: IllegalArgumentException) { defer(claim, RecoveryReason.UNKNOWN, true); return }
                if (!compatible(claim, receipt) || receipt.kind != command.kind) { store.reconcileClaimEvidence(claim, receipt, RecoveryReason.CONTRACT_INCOMPATIBLE); return }
                val received = policy.resolve(CommandKind.valueOf(command.kind.name), receipt)
                when (received) {
                    RecoveryDisposition.ApplyEvidence, RecoveryDisposition.WaitAndGet -> apply.execute(claim, receipt)
                    is RecoveryDisposition.ReconciliationRequired -> store.reconcileClaimEvidence(claim, receipt, received.reason)
                    else -> defer(claim, RecoveryReason.UNKNOWN, true)
                }
            }
            is RecoveryDisposition.ReconciliationRequired -> result.second?.let { store.reconcileClaimEvidence(claim, it, disposition.reason) }
                ?: defer(claim, disposition.reason, true)
            RecoveryDisposition.Unknown -> defer(claim, RecoveryReason.UNKNOWN, true)
        }
    }
    private fun remoteFault(claim: ClaimedSaleCommand, fault: StoreCoreRemoteFault) {
        when (fault.failureCode) {
            StoreCoreFailureCode.CONFLICT -> defer(claim, RecoveryReason.REMOTE_PENDING)
            StoreCoreFailureCode.IDEMPOTENCY_PAYLOAD_MISMATCH -> defer(claim, RecoveryReason.PAYLOAD_MISMATCH, true)
            StoreCoreFailureCode.EXPIRED, StoreCoreFailureCode.OPERATION_RETIRED -> defer(claim, RecoveryReason.EXPIRED, true)
            StoreCoreFailureCode.NOT_FOUND -> if (claim.uncertain && claim.command.kind == StoreCoreOperationKind.RESERVE) defer(claim, RecoveryReason.REMOTE_PENDING)
                else defer(claim, RecoveryReason.NOT_FOUND_TERMINAL, true)
            StoreCoreFailureCode.INSUFFICIENT_STOCK, StoreCoreFailureCode.CATALOG_VERSION_STALE, StoreCoreFailureCode.VALIDATION -> defer(claim, RecoveryReason.UNKNOWN, true)
            StoreCoreFailureCode.UNKNOWN -> defer(claim, if (fault.retryable) RecoveryReason.REMOTE_UNAVAILABLE else RecoveryReason.UNKNOWN, !fault.retryable)
        }
    }
}
