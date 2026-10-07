package com.blackstore.application.sales

import com.blackstore.application.storecore.StoreCoreRecoveryPolicy
import com.blackstore.application.storecore.StoreCoreRequestHash
import com.blackstore.domain.companion.CompanionEnvironment
import com.blackstore.domain.compliance.FiscalBoundaryPolicy
import com.blackstore.domain.catalog.CatalogReserveLinePolicy
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
import com.blackstore.domain.port.out.sales.SaleOperationQuery
import com.blackstore.domain.port.out.counter.CounterEntryStore
import com.blackstore.domain.sales.OperationLedger
import com.blackstore.domain.sales.PaymentSnapshot
import com.blackstore.domain.sales.PaymentTransitionPolicy
import com.blackstore.domain.sales.PaymentLedgerSemantics
import com.blackstore.domain.sales.TransitionDecision
import com.blackstore.domain.port.out.accounting.AccountingRuntimeQuery
import java.util.concurrent.ConcurrentHashMap
import com.blackstore.application.identity.AuthorizeStaffAction
import com.blackstore.domain.identity.*

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
    private val counterEntryStore: CounterEntryStore? = null,
    private val coordinator: LocalSaleCoordinator = LocalSaleCoordinator.local,
    private val authorization: AuthorizeStaffAction? = null,
    @Value("\${blackstore.persistence.enabled:false}") private val persistenceEnabled: Boolean = false,
    private val paymentLedgerSemantics: PaymentLedgerSemantics = PaymentLedgerSemantics.LegacyCapturedOnly,
    private val accountingRuntime: AccountingRuntimeQuery? = null,
) : SaleOperationQuery {
    private fun currentPaymentLedgerSemantics() = accountingRuntime?.paymentLedgerSemantics() ?: paymentLedgerSemantics
    private fun paymentPolicy() = PaymentTransitionPolicy(currentPaymentLedgerSemantics())
    private fun durable(): com.blackstore.domain.port.out.sales.DurableSaleStore? =
        (saleRecordStore as? com.blackstore.domain.port.out.sales.DurableSaleStore).also {
            check(!persistenceEnabled || it != null) { "durable persistence unavailable" }
        }
    private fun dispatchDurable(saga: SaleSaga, kind: com.blackstore.domain.sales.CommandKind): SaleSaga {
        val store = requireNotNull(durable())
        DurableSaleCommandFlow(store, inventoryPort).runOperation(saga.quadruple.operationId, kind)
        return requireNotNull(store.findDurable(saga.quadruple.operationId)).saga
    }
    private val catalogPolicy = CatalogSalePolicy()
    private val reserveLinePolicy = CatalogReserveLinePolicy()
    private val retiredPolicy = RetiredOperationPolicy()
    private val fiscalPolicy = FiscalBoundaryPolicy()
    private val sales = ConcurrentHashMap<OperationQuadruple, SaleSaga>()
    private val reserveLinesByOperation = ConcurrentHashMap<OperationQuadruple, List<ReserveLineCommand>>()

    fun beginReserve(staff: AuthenticatedStaff, quadruple: OperationQuadruple, cashSessionId: Long, lines: List<ReserveLineCommand>, ticketLines: List<TicketLine>, now: Instant, reason: String?): SaleSaga {
        return coordinator.coordinate(quadruple) {
            authority().reserve(staff,quadruple,cashSessionId,findSale(quadruple)?.cashSessionId,reason)
            beginReserveCoordinated(quadruple,cashSessionId,lines,ticketLines,now,staff.id.value,
                com.blackstore.domain.sales.SaleStaffCommandAudit(com.blackstore.domain.sales.SaleStaffCommandEvent.RESERVE_REQUESTED,staff.id,reason))
        }
    }
    fun stored(staff: AuthenticatedStaff, operationId: String): SaleSaga? { authority().sale(staff,StaffPermission.SaleRead,operationId); return stored(operationId) }
    fun commit(staff: AuthenticatedStaff, operationId: String, reason: String?): SaleSaga {
        val identity=resolveSale(operationId)?.quadruple ?: throw StaffSecurityException(StaffSecurityFailure.NOT_FOUND)
        return coordinator.coordinate(identity) {
            val cash=authority().sale(staff,StaffPermission.SaleCommit,identity,reason)
            if (cash.id != findSale(identity)?.cashSessionId) throw StaffSecurityException(StaffSecurityFailure.NOT_FOUND)
            commitCoordinated(identity,Instant.now(),com.blackstore.domain.sales.SaleStaffCommandAudit(com.blackstore.domain.sales.SaleStaffCommandEvent.COMMIT_REQUESTED,staff.id,reason))
        }
    }
    fun release(staff: AuthenticatedStaff, operationId: String, reason: String?): SaleSaga {
        val identity=resolveSale(operationId)?.quadruple ?: throw StaffSecurityException(StaffSecurityFailure.NOT_FOUND)
        return coordinator.coordinate(identity) {
            val cash=authority().sale(staff,StaffPermission.SaleRelease,identity,reason)
            if (cash.id != findSale(identity)?.cashSessionId) throw StaffSecurityException(StaffSecurityFailure.NOT_FOUND)
            releaseCoordinated(identity,com.blackstore.domain.sales.SaleStaffCommandAudit(com.blackstore.domain.sales.SaleStaffCommandEvent.RELEASE_REQUESTED,staff.id,reason))
        }
    }
    private fun authority()=authorization ?: throw StaffSecurityException(StaffSecurityFailure.IDENTITY_UNAVAILABLE)

    fun detail(staff: AuthenticatedStaff, operationId: String): DurableSaleView? {
        val cash = authority().sale(staff, StaffPermission.SaleRead, operationId)
        val stored = durable()?.findDurable(operationId) ?: return null
        if (stored.saga.cashSessionId != cash.id) throw StaffSecurityException(StaffSecurityFailure.NOT_FOUND)
        val entries = ledger(stored.saga.quadruple)
        val semantics = currentPaymentLedgerSemantics()
        val policy = PaymentTransitionPolicy(semantics)
        return DurableSaleView(stored, cash.cashierId.value, policy.snapshot(stored.saga, entries),
            (entries as? OperationLedger.Known)?.entries.orEmpty(),
            com.blackstore.domain.sales.DurableSaleActions(semantics).allowed(stored, entries, cash.open))
    }

    fun list(staff: AuthenticatedStaff, cursor: Long, limit: Int, state: com.blackstore.domain.sales.DurableSaleState?): DurableSalePage {
        authority().permission(staff, StaffPermission.SaleRead)
        require(cursor >= 0 && limit in 1..100)
        val store = durable() ?: return DurableSalePage(emptyList(), null)
        var position = cursor
        val visible = mutableListOf<DurableSaleView>()
        while (visible.size < limit) {
            val batch = store.listDurable(position, minOf(100, limit - visible.size))
            if (batch.isEmpty()) return DurableSalePage(visible, null)
            for (sale in batch) {
                position = sale.projectionId
                if (state != null && sale.state != state) continue
                try { detail(staff, sale.saga.quadruple.operationId)?.let(visible::add) }
                catch (denied: StaffSecurityException) { if (denied.failure != StaffSecurityFailure.NOT_FOUND) throw denied }
            }
        }
        return DurableSalePage(visible, position)
    }

    fun beginReserve(quadruple: OperationQuadruple, cashSessionId: Long, lines: List<ReserveLineCommand>, ticketLines: List<TicketLine> = emptyList(), now: Instant, createdBy: Long? = null): SaleSaga =
        coordinator.coordinate(quadruple) { beginReserveCoordinated(quadruple, cashSessionId, lines, ticketLines, now, createdBy) }

    private fun beginReserveCoordinated(
        quadruple: OperationQuadruple,
        cashSessionId: Long,
        lines: List<ReserveLineCommand>,
        ticketLines: List<TicketLine> = emptyList(),
        now: Instant,
        createdBy: Long? = null,
        admission: com.blackstore.domain.sales.SaleStaffCommandAudit? = null,
    ): SaleSaga {
        durable()?.let { store ->
            val requestHash = ReserveRequestFingerprint.hash(quadruple, cashSessionId, lines, ticketLines, admission?.reason)
            val existing = store.findDurable(quadruple.operationId)
            if (existing != null) {
                require(existing.saga.quadruple == quadruple && existing.saga.cashSessionId == cashSessionId) { "idempotency payload mismatch" }
                val original = existing.saga.outbox.singleOrNull { it.kind == StoreCoreOperationKind.RESERVE }
                val payload = original?.payload as? com.blackstore.domain.sales.CanonicalCommandPayload.Reserve
                require(payload != null && original.requestHash == requestHash) { "idempotency payload mismatch" }
                if (existing.saga.status != SaleStatus.PENDING_RESERVATION || existing.saga.blockSameOperationRepost || existing.saga.retired) return existing.saga
                return dispatchDurable(existing.saga, com.blackstore.domain.sales.CommandKind.RESERVE)
            }
            val snapshot = catalogPort.currentSnapshot()
            catalogPolicy.assertSaleAllowed(snapshot, now)
            val resolved = reserveLinePolicy.resolve(snapshot, lines, ticketLines)
            retiredPolicy.assertCanPost(retirementPort.isRetired(quadruple.operationId))
            val pending = SaleSaga(quadruple, cashSessionId, lines = ticketLines, createdBy = createdBy, staffCommandAudit = admission).withReserveCommand(
                OutboxCommand(quadruple, StoreCoreOperationKind.RESERVE, canonicalPath, contractVersion, openapiDigest,
                    requestHash,
                    payload = com.blackstore.domain.sales.CanonicalCommandPayload.Reserve(requireNotNull(snapshot).version,
                        resolved.map { com.blackstore.domain.sales.CanonicalReserveLine(it.variantId,it.quantity,it.expectedPriceVersion) })))
            saleRecordStore.recordIntentAndOutbox(pending)
            return dispatchDurable(pending, com.blackstore.domain.sales.CommandKind.RESERVE)
        }
        com.blackstore.domain.sales.MoneyPolicy.normalize(ticketLines.fold(java.math.BigDecimal.ZERO) { sum, line -> sum + line.effectiveUnitPrice.multiply(java.math.BigDecimal(line.quantity)) })
        val resolved =
            reserveLinesByOperation[quadruple]
                ?: reserveLinePolicy.resolve(catalogPort.currentSnapshot(), lines, ticketLines)
        reserveLinesByOperation[quadruple] = resolved
        sales[quadruple]?.let { existing ->
            retiredPolicy.assertCanPost(existing.retired || retirementPort.isRetired(quadruple.operationId))
            if (existing.blockSameOperationRepost) return existing
            if (existing.status != SaleStatus.PENDING_RESERVATION || existing.evidence != null) return existing
            if (existing.recoverWithGet) return recoverReserve(existing)
        }
        catalogPolicy.assertSaleAllowed(catalogPort.currentSnapshot(), now)
        retiredPolicy.assertCanPost(retirementPort.isRetired(quadruple.operationId))
        val already = sales[quadruple]
        val pending =
            already
                ?: SaleSaga(quadruple = quadruple, cashSessionId = cashSessionId, lines = ticketLines, createdBy = createdBy)
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
        if (already == null) {
            saleRecordStore.recordIntentAndOutbox(pending)
        }
        sales[quadruple] = pending
        return callReserve(pending, resolved)
    }

    fun commit(operationId: String, now: Instant = Instant.now()): SaleSaga {
        val identity = resolveSale(operationId)?.quadruple ?: throw IllegalArgumentException("sale missing or ambiguous")
        return coordinator.coordinate(identity) { commitCoordinated(identity, now) }
    }

    private fun commitCoordinated(identity: OperationQuadruple, now: Instant, audit: com.blackstore.domain.sales.SaleStaffCommandAudit? = null): SaleSaga {
        val current = findSale(identity) ?: throw IllegalArgumentException("sale missing or ambiguous")
        retiredPolicy.assertCanPost(current.retired || retirementPort.isRetired(identity.operationId))
        val decision = paymentPolicy().terminal(current, ledger(identity), StoreCoreOperationKind.COMMIT)
        decision.assertAllowed()
        if (decision == TransitionDecision.RecoverExistingCommand || decision == TransitionDecision.TerminalReplay) assertOriginalCommand(current, StoreCoreOperationKind.COMMIT)
        if (decision == TransitionDecision.TerminalReplay) return current
        fiscalPolicy.assertCanCommit(
            CompanionEnvironment.valueOf(environmentName),
            current.fiscalStatus,
            authorization = null,
            now = now,
        )
        if (decision == TransitionDecision.RecoverExistingCommand) {
            if (durable() != null) return dispatchDurable(current, com.blackstore.domain.sales.CommandKind.COMMIT)
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
        val attributed = pending.copy(staffCommandAudit = audit)
        saleRecordStore.recordCommitPending(attributed)
        if (durable() != null) return dispatchDurable(attributed, com.blackstore.domain.sales.CommandKind.COMMIT)
        sales[identity] = attributed
        return callCommit(attributed)
    }

    fun release(operationId: String): SaleSaga {
        val identity = resolveSale(operationId)?.quadruple ?: throw IllegalArgumentException("sale missing or ambiguous")
        return coordinator.coordinate(identity) { releaseCoordinated(identity) }
    }

    private fun releaseCoordinated(identity: OperationQuadruple, audit: com.blackstore.domain.sales.SaleStaffCommandAudit? = null): SaleSaga {
        val current = findSale(identity) ?: throw IllegalArgumentException("sale missing or ambiguous")
        if (current.status == SaleStatus.COMMITTED) {
            throw IllegalArgumentException("a committed sale is not released")
        }
        retiredPolicy.assertCanPost(current.retired || retirementPort.isRetired(identity.operationId))
        val decision = paymentPolicy().terminal(current, ledger(identity), StoreCoreOperationKind.RELEASE)
        decision.assertAllowed()
        if (decision == TransitionDecision.RecoverExistingCommand || decision == TransitionDecision.TerminalReplay) assertOriginalCommand(current, StoreCoreOperationKind.RELEASE)
        if (decision == TransitionDecision.TerminalReplay) return current
        if (decision == TransitionDecision.RecoverExistingCommand) {
            if (durable() != null) return dispatchDurable(current, com.blackstore.domain.sales.CommandKind.RELEASE)
            return recoverRelease(current)
        }
        val pending =
            when (current.status) {
                SaleStatus.RESERVED, SaleStatus.PAYMENT_CAPTURED ->
                    current.withCommand(command(current, StoreCoreOperationKind.RELEASE)).markReleasePending()
                SaleStatus.RELEASE_PENDING -> current
                else -> throw IllegalArgumentException("sale ${current.status} cannot release")
            }
        val attributed = pending.copy(staffCommandAudit = audit)
        saleRecordStore.recordReleasePending(attributed)
        if (durable() != null) return dispatchDurable(attributed, com.blackstore.domain.sales.CommandKind.RELEASE)
        sales[identity] = attributed
        return callRelease(attributed)
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
                        reservationRef = pending.outbox.single { it.kind == StoreCoreOperationKind.COMMIT }.reservationRef ?: error("original command body is missing"),
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
                        reservationRef = pending.outbox.single { it.kind == StoreCoreOperationKind.RELEASE }.reservationRef ?: error("original command body is missing"),
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
        assertReceipt(pending, receipt)
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
                    return callReserve(retry, reserveLinesByOperation[retry.quadruple].orEmpty())
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
        assertReceipt(pending, receipt)
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
        sales[saga.quadruple] = saga
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
        return reserve.copy(kind = kind, reservationRef = saga.evidence?.reservationRef, requestHash = StoreCoreRequestHash.hex(kind, saga.quadruple.operationId + saga.evidence?.reservationRef),
            payload = saga.evidence?.reservationRef?.let { com.blackstore.domain.sales.CanonicalCommandPayload.Terminal(it) })
    }

    private fun assertOriginalCommand(sale: SaleSaga, kind: StoreCoreOperationKind) {
        val original = sale.outbox.single { it.kind == kind }
        require(original.canonicalPath == canonicalPath && original.contractVersion == contractVersion && original.openapiDigest == openapiDigest &&
            original.requestHash == StoreCoreRequestHash.hex(kind, sale.quadruple.operationId + original.reservationRef)) { "original command evidence is incompatible" }
    }

    private fun assertReceipt(sale: SaleSaga, receipt: StoreCoreOperationReceipt) {
        require(receipt.quadruple == sale.quadruple && receipt.contract.canonicalPath == canonicalPath && receipt.contract.contractVersion == contractVersion &&
            (receipt.state == StoreCoreOperationState.PENDING || receipt.contract.openapiDigestSha256 == openapiDigest)) { "receipt identity or contract evidence mismatch" }
    }

    fun retire(operationId: String) {
        retirementPort.markRetired(operationId)
        resolveSale(operationId)?.let { sale -> coordinator.coordinate(sale.quadruple) { sales[sale.quadruple] = sale.markRetired() } }
    }

    fun stored(operationId: String): SaleSaga? = resolveSale(operationId)
    override fun resolveSale(operationId: String): SaleSaga? = durable()?.let { it.findDurable(operationId)?.saga }
        ?: if (durable() == null) sales.values.filter { it.quadruple.operationId == operationId }.singleOrNull() else null
    override fun findSale(identity: OperationQuadruple): SaleSaga? =
        durable()?.findDurable(identity.operationId)?.saga?.takeIf { it.quadruple == identity }
            ?: if (durable() == null) sales[identity] else null
    private fun ledger(identity: OperationQuadruple): OperationLedger = counterEntryStore?.paymentLedger(identity) ?: OperationLedger.Unknown
    fun paymentSnapshot(sale: SaleSaga): PaymentSnapshot = coordinator.coordinate(sale.quadruple) { paymentPolicy().snapshot(findSale(sale.quadruple) ?: sale, ledger(sale.quadruple)) }
}
