package com.blackstore.application.sales

import com.blackstore.application.storecore.StoreCoreRecoveryPolicy
import com.blackstore.application.storecore.StoreCoreRequestHash
import com.blackstore.domain.companion.CompanionEnvironment
import com.blackstore.domain.compliance.FiscalBoundaryPolicy
import com.blackstore.domain.catalog.CatalogSalePolicy
import com.blackstore.domain.exception.StoreCoreRemoteFault
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.model.StoreCoreCanonicalContract
import com.blackstore.domain.model.StoreCoreOperationKind
import com.blackstore.domain.model.StoreCoreOperationReceipt
import com.blackstore.domain.model.StoreCoreOperationState
import com.blackstore.domain.model.StoreCoreRecoveryAction
import com.blackstore.domain.port.out.storecore.CommitInventoryCommand
import com.blackstore.domain.port.out.storecore.OperationRetirementPort
import com.blackstore.domain.port.out.storecore.ReleaseInventoryCommand
import com.blackstore.domain.port.out.storecore.ReserveInventoryCommand
import com.blackstore.domain.port.out.storecore.ReserveLineCommand
import com.blackstore.domain.port.out.storecore.StoreCoreCatalogPort
import com.blackstore.domain.port.out.storecore.StoreCoreInventoryPort
import com.blackstore.domain.port.out.sales.SaleRecordStore
import com.blackstore.domain.sales.OutboxCommand
import com.blackstore.domain.sales.RemoteEvidence
import com.blackstore.domain.sales.RetiredOperationPolicy
import com.blackstore.domain.sales.SaleSaga
import com.blackstore.domain.sales.SaleStatus
import com.blackstore.domain.sales.TicketLine
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class LocalSaleSagaService(
    private val catalogPort: StoreCoreCatalogPort,
    private val inventoryPort: StoreCoreInventoryPort,
    private val retirementPort: OperationRetirementPort,
    private val saleRecordStore: SaleRecordStore,
    @Value("\${blackstore.storecore.contract.canonical-path}") private val canonicalPath: String,
    @Value("\${blackstore.storecore.contract.version}") private val contractVersion: String,
    @Value("\${blackstore.companion.stored.environment}") private val environmentName: String = "TEST",
    @Value("\${blackstore.storecore.contract.sha256}") private val openapiDigest: String = StoreCoreCanonicalContract.SHA256,
) {
    private val catalogPolicy = CatalogSalePolicy()
    private val retiredPolicy = RetiredOperationPolicy()
    private val fiscalPolicy = FiscalBoundaryPolicy()
    private val sales = linkedMapOf<String, SaleSaga>()
    private val reserveLinesByOperation = linkedMapOf<String, List<ReserveLineCommand>>()

    fun beginReserve(
        quadruple: OperationQuadruple,
        cashSessionId: Long,
        lines: List<ReserveLineCommand>,
        ticketLines: List<TicketLine> = emptyList(),
        now: Instant,
    ): SaleSaga {
        reserveLinesByOperation[quadruple.operationId] = lines
        sales[quadruple.operationId]?.let { existing ->
            retiredPolicy.assertCanPost(existing.retired || retirementPort.isRetired(quadruple.operationId))
            if (existing.blockSameOperationRepost) return existing
            if (existing.status != SaleStatus.PENDING_RESERVATION || existing.evidence != null) return existing
            if (existing.recoverWithGet) return recoverReserve(existing)
        }
        catalogPolicy.assertSaleAllowed(catalogPort.currentSnapshot(), now)
        retiredPolicy.assertCanPost(retirementPort.isRetired(quadruple.operationId))
        val already = sales[quadruple.operationId]
        val pending =
            already
                ?: SaleSaga(quadruple = quadruple, cashSessionId = cashSessionId, lines = ticketLines)
                    .withReserveCommand(
                        OutboxCommand(
                            quadruple = quadruple,
                            kind = StoreCoreOperationKind.RESERVE,
                            canonicalPath = canonicalPath,
                            contractVersion = contractVersion,
                            openapiDigest = openapiDigest,
                            requestHash = StoreCoreRequestHash.hex(StoreCoreOperationKind.RESERVE, quadruple.operationId),
                        ),
                    )
        sales[quadruple.operationId] = pending
        if (already == null) {
            saleRecordStore.recordIntentAndOutbox(pending)
        }
        return callReserve(pending, lines)
    }

    fun commit(operationId: String, now: Instant = Instant.now()): SaleSaga {
        val current = sales[operationId] ?: throw IllegalArgumentException("sale $operationId is not in this process")
        if (current.status == SaleStatus.COMMITTED) return current
        retiredPolicy.assertCanPost(current.retired || retirementPort.isRetired(operationId))
        if (current.status == SaleStatus.RECONCILIATION_REQUIRED || current.blockSameOperationRepost && current.status == SaleStatus.COMMIT_PENDING) {
            return current
        }
        fiscalPolicy.assertCanCommit(
            CompanionEnvironment.valueOf(environmentName),
            current.fiscalStatus,
            authorization = null,
            now = now,
        )
        if (current.status == SaleStatus.COMMIT_PENDING && current.recoverWithGet) {
            return recoverCommit(current)
        }
        val pending =
            when (current.status) {
                SaleStatus.RESERVED ->
                    current.markPaymentCaptured().withCommand(command(current, StoreCoreOperationKind.COMMIT)).markCommitPending()
                SaleStatus.PAYMENT_CAPTURED ->
                    current.withCommand(command(current, StoreCoreOperationKind.COMMIT)).markCommitPending()
                SaleStatus.COMMIT_PENDING -> current
                else -> throw IllegalArgumentException("sale ${current.status} cannot commit")
            }
        sales[operationId] = pending
        saleRecordStore.recordCommitPending(pending)
        return callCommit(pending)
    }

    fun release(operationId: String): SaleSaga {
        val current = sales[operationId] ?: throw IllegalArgumentException("sale $operationId is not in this process")
        if (current.status == SaleStatus.RELEASED) return current
        if (current.status == SaleStatus.COMMITTED) {
            throw IllegalArgumentException("a committed sale is not released")
        }
        retiredPolicy.assertCanPost(current.retired || retirementPort.isRetired(operationId))
        if (current.status == SaleStatus.RECONCILIATION_REQUIRED || current.blockSameOperationRepost && current.status == SaleStatus.RELEASE_PENDING) {
            return current
        }
        if (current.status == SaleStatus.RELEASE_PENDING && current.recoverWithGet) {
            return recoverRelease(current)
        }
        val pending =
            when (current.status) {
                SaleStatus.RESERVED, SaleStatus.PAYMENT_CAPTURED ->
                    current.withCommand(command(current, StoreCoreOperationKind.RELEASE)).markReleasePending()
                SaleStatus.RELEASE_PENDING -> current
                else -> throw IllegalArgumentException("sale ${current.status} cannot release")
            }
        sales[operationId] = pending
        saleRecordStore.recordReleasePending(pending)
        return callRelease(pending)
    }

    private fun callReserve(pending: SaleSaga, lines: List<ReserveLineCommand>): SaleSaga =
        try {
            applyReserveReceipt(
                pending,
                inventoryPort.reserve(
                    ReserveInventoryCommand(
                        quadruple = pending.quadruple,
                        catalogVersion = catalogPort.currentSnapshot()?.version ?: "unknown",
                        lines = lines,
                    ),
                ),
            )
        } catch (ex: IllegalStateException) {
            pending
        } catch (ex: StoreCoreRemoteFault) {
            applyReserveFault(pending, ex)
        }

    private fun recoverReserve(pending: SaleSaga): SaleSaga {
        return try {
            val recovered = inventoryPort.getOperation(pending.quadruple) ?: return pending
            applyReserveReceipt(pending, recovered)
        } catch (ex: StoreCoreRemoteFault) {
            applyReserveFault(pending, ex)
        }
    }

    private fun callCommit(pending: SaleSaga): SaleSaga =
        try {
            applyTerminalReceipt(
                pending,
                inventoryPort.commit(
                    CommitInventoryCommand(
                        quadruple = pending.quadruple,
                        reservationRef = pending.evidence?.reservationRef ?: error("reservation evidence is missing"),
                    ),
                ),
                expected = StoreCoreOperationState.COMMITTED,
            ) { it.markCommitted() }
        } catch (ex: StoreCoreRemoteFault) {
            applyTerminalFault(pending, ex, StoreCoreOperationKind.COMMIT)
        }

    private fun recoverCommit(pending: SaleSaga): SaleSaga {
        return try {
            val recovered = inventoryPort.getOperation(pending.quadruple) ?: return pending
            applyTerminalReceipt(pending, recovered, StoreCoreOperationState.COMMITTED) { it.markCommitted() }
        } catch (ex: StoreCoreRemoteFault) {
            applyTerminalFault(pending, ex, StoreCoreOperationKind.COMMIT)
        }
    }

    private fun callRelease(pending: SaleSaga): SaleSaga =
        try {
            applyTerminalReceipt(
                pending,
                inventoryPort.release(
                    ReleaseInventoryCommand(
                        quadruple = pending.quadruple,
                        reservationRef = pending.evidence?.reservationRef ?: error("reservation evidence is missing"),
                    ),
                ),
                expected = StoreCoreOperationState.RELEASED,
            ) { it.markReleased() }
        } catch (ex: StoreCoreRemoteFault) {
            applyTerminalFault(pending, ex, StoreCoreOperationKind.RELEASE)
        }

    private fun recoverRelease(pending: SaleSaga): SaleSaga {
        return try {
            val recovered = inventoryPort.getOperation(pending.quadruple) ?: return pending
            applyTerminalReceipt(pending, recovered, StoreCoreOperationState.RELEASED) { it.markReleased() }
        } catch (ex: StoreCoreRemoteFault) {
            applyTerminalFault(pending, ex, StoreCoreOperationKind.RELEASE)
        }
    }

    private fun applyReserveReceipt(pending: SaleSaga, receipt: StoreCoreOperationReceipt): SaleSaga {
        recordReceiptInbox(pending, receipt)
        return when (StoreCoreRecoveryPolicy.actionFor(receipt)) {
            StoreCoreRecoveryAction.RETAIN_PENDING -> store(pending.copy(recoverWithGet = false))
            StoreCoreRecoveryAction.RECORD_RECONCILIATION_REQUIRED -> {
                val evidence = StoreCoreRecoveryPolicy.evidenceFrom(receipt)
                val reconciled =
                    pending.markReconciliationRequired(
                        evidence.reconciliationReason ?: "EXPIRED remote tuple requires reconciliation",
                        toRemoteEvidence(receipt),
                    )
                saleRecordStore.recordReconciliationRequired(reconciled)
                store(reconciled)
            }
            else -> {
                val stored = pending.applyRemoteDurable(receipt.state, toRemoteEvidence(receipt))
                if (pending.status == SaleStatus.PENDING_RESERVATION && stored.status == SaleStatus.RESERVED) {
                    saleRecordStore.recordReserved(stored)
                } else {
                    recordStoredTerminal(stored)
                }
                store(stored)
            }
        }
    }

    private fun applyReserveFault(pending: SaleSaga, fault: StoreCoreRemoteFault): SaleSaga {
        saleRecordStore.recordInbox(
            pending,
            StoreCoreRequestHash.hex(StoreCoreOperationKind.RESERVE, pending.quadruple.operationId + fault.errorCode),
            "RESERVE",
            "PENDING",
        )
        return when (StoreCoreRecoveryPolicy.actionForError(fault.errorCode)) {
            StoreCoreRecoveryAction.GET_SAME_QUADRUPLE -> {
                val recovered = inventoryPort.getOperation(pending.quadruple) ?: return store(pending.markGetOnly())
                val afterGet = applyReserveReceipt(pending, recovered)
                if (shouldSameBodyPost(StoreCoreOperationKind.RESERVE, afterGet, recovered)) {
                    val retry = store(afterGet.copy(sameBodyRetries = afterGet.sameBodyRetries + 1, recoverWithGet = false))
                    return callReserve(retry, reserveLinesByOperation[retry.quadruple.operationId].orEmpty())
                }
                afterGet
            }
            StoreCoreRecoveryAction.NEVER_REPOST -> {
                retirementPort.markRetired(pending.quadruple.operationId)
                store(pending.markRetired().markSameOperationBlocked())
            }
            StoreCoreRecoveryAction.NEW_OPERATION_SAME_SALE,
            StoreCoreRecoveryAction.RETAIN_DURABLE_EVIDENCE,
            -> store(pending.markSameOperationBlocked())
            else -> store(pending.markSameOperationBlocked())
        }
    }

    private fun applyTerminalReceipt(
        pending: SaleSaga,
        receipt: StoreCoreOperationReceipt,
        expected: StoreCoreOperationState,
        complete: (SaleSaga) -> SaleSaga,
    ): SaleSaga {
        recordReceiptInbox(pending, receipt)
        return when (StoreCoreRecoveryPolicy.actionFor(receipt)) {
            StoreCoreRecoveryAction.RETAIN_PENDING -> store(pending.markGetOnly())
            StoreCoreRecoveryAction.RECORD_RECONCILIATION_REQUIRED -> {
                val evidence = StoreCoreRecoveryPolicy.evidenceFrom(receipt)
                val reconciled =
                    pending.markReconciliationRequired(
                        evidence.reconciliationReason ?: "EXPIRED remote tuple requires reconciliation",
                        toRemoteEvidence(receipt),
                    )
                saleRecordStore.recordReconciliationRequired(reconciled)
                store(reconciled)
            }
            else -> {
                val stored = pending.applyRemoteDurable(receipt.state, toRemoteEvidence(receipt))
                if (receipt.state == expected && stored.status == pending.status) {
                    val done = complete(pending.copy(evidence = stored.evidence, recoverWithGet = false))
                    recordStoredTerminal(done)
                    return store(done)
                }
                recordStoredTerminal(stored)
                store(stored)
            }
        }
    }

    private fun applyTerminalFault(pending: SaleSaga, fault: StoreCoreRemoteFault, kind: StoreCoreOperationKind): SaleSaga {
        saleRecordStore.recordInbox(
            pending,
            StoreCoreRequestHash.hex(kind, pending.quadruple.operationId + fault.errorCode),
            kind.name,
            "PENDING",
        )
        return when (StoreCoreRecoveryPolicy.actionForError(fault.errorCode)) {
            StoreCoreRecoveryAction.GET_SAME_QUADRUPLE -> {
                val recovered = inventoryPort.getOperation(pending.quadruple) ?: return store(pending.markGetOnly())
                val afterGet =
                    if (kind == StoreCoreOperationKind.COMMIT) {
                        applyTerminalReceipt(pending, recovered, StoreCoreOperationState.COMMITTED) { it.markCommitted() }
                    } else {
                        applyTerminalReceipt(pending, recovered, StoreCoreOperationState.RELEASED) { it.markReleased() }
                    }
                if (shouldSameBodyPost(kind, afterGet, recovered)) {
                    val retry = store(afterGet.copy(sameBodyRetries = afterGet.sameBodyRetries + 1, recoverWithGet = false))
                    return if (kind == StoreCoreOperationKind.COMMIT) callCommit(retry) else callRelease(retry)
                }
                afterGet
            }
            StoreCoreRecoveryAction.NEVER_REPOST -> {
                retirementPort.markRetired(pending.quadruple.operationId)
                store(pending.markRetired().markSameOperationBlocked())
            }
            StoreCoreRecoveryAction.NEW_OPERATION_SAME_SALE,
            StoreCoreRecoveryAction.RETAIN_DURABLE_EVIDENCE,
            -> store(pending.markSameOperationBlocked())
            else -> store(pending.markSameOperationBlocked())
        }
    }

    private fun shouldSameBodyPost(
        kind: StoreCoreOperationKind,
        afterGet: SaleSaga,
        recovered: StoreCoreOperationReceipt,
    ): Boolean {
        if (afterGet.sameBodyRetries >= 1 || afterGet.retired || afterGet.blockSameOperationRepost) return false
        return when (kind) {
            StoreCoreOperationKind.RESERVE ->
                afterGet.status == SaleStatus.PENDING_RESERVATION && recovered.state == StoreCoreOperationState.PENDING
            StoreCoreOperationKind.COMMIT ->
                afterGet.status == SaleStatus.COMMIT_PENDING &&
                    recovered.state != StoreCoreOperationState.COMMITTED &&
                    recovered.state != StoreCoreOperationState.RELEASED
            StoreCoreOperationKind.RELEASE ->
                afterGet.status == SaleStatus.RELEASE_PENDING &&
                    recovered.state != StoreCoreOperationState.RELEASED &&
                    recovered.state != StoreCoreOperationState.COMMITTED
        }
    }

    private fun recordReceiptInbox(pending: SaleSaga, receipt: StoreCoreOperationReceipt) {
        saleRecordStore.recordInbox(
            pending,
            StoreCoreRequestHash.hex(receipt.kind, pending.quadruple.operationId + receipt.state.name),
            receipt.kind.name,
            receipt.state.name,
            receipt.receipt,
            receipt.reservationRef,
        )
    }

    private fun recordStoredTerminal(stored: SaleSaga) {
        when (stored.status) {
            SaleStatus.COMMITTED -> saleRecordStore.recordCommitted(stored)
            SaleStatus.RELEASED -> saleRecordStore.recordReleased(stored)
            else -> Unit
        }
    }

    private fun store(saga: SaleSaga): SaleSaga {
        sales[saga.quadruple.operationId] = saga
        return saga
    }

    private fun toRemoteEvidence(receipt: StoreCoreOperationReceipt) =
        RemoteEvidence(
            reservationRef = receipt.reservationRef ?: error("durable receipt missing ref"),
            receipt = receipt.receipt ?: error("durable receipt missing receipt"),
            contractVersion = receipt.contract.contractVersion,
            openapiDigest = receipt.contract.openapiDigestSha256 ?: openapiDigest,
            acceptedPriceVersions = receipt.acceptedPriceVersions,
            expiresAt = receipt.expiresAt,
        )

    private fun command(saga: SaleSaga, kind: StoreCoreOperationKind): OutboxCommand {
        val reserve = saga.outbox.first { it.kind == StoreCoreOperationKind.RESERVE }
        return reserve.copy(kind = kind, requestHash = StoreCoreRequestHash.hex(kind, saga.quadruple.operationId))
    }

    fun retire(operationId: String) {
        retirementPort.markRetired(operationId)
        sales[operationId]?.let { sales[operationId] = it.markRetired() }
    }

    fun stored(operationId: String): SaleSaga? = sales[operationId]
}
