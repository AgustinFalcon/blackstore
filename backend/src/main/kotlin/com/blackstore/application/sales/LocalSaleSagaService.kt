package com.blackstore.application.sales

import com.blackstore.domain.companion.CompanionEnvironment
import com.blackstore.domain.compliance.FiscalBoundaryPolicy
import com.blackstore.domain.catalog.CatalogSalePolicy
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.model.StoreCoreOperationKind
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
) {
    private val catalogPolicy = CatalogSalePolicy()
    private val retiredPolicy = RetiredOperationPolicy()
    private val fiscalPolicy = FiscalBoundaryPolicy()
    private val sales = linkedMapOf<String, SaleSaga>()

    fun beginReserve(
        quadruple: OperationQuadruple,
        cashSessionId: Long,
        lines: List<ReserveLineCommand>,
        ticketLines: List<TicketLine> = emptyList(),
        now: Instant,
    ): SaleSaga {
        sales[quadruple.operationId]?.let { existing ->
            if (existing.status != SaleStatus.PENDING_RESERVATION || existing.evidence != null) {
                return existing
            }
        }
        catalogPolicy.assertSaleAllowed(catalogPort.currentSnapshot(), now)
        retiredPolicy.assertCanPost(retirementPort.isRetired(quadruple.operationId))
        val pending =
            SaleSaga(quadruple = quadruple, cashSessionId = cashSessionId, lines = ticketLines)
                .withReserveCommand(
                    OutboxCommand(
                        quadruple = quadruple,
                        kind = StoreCoreOperationKind.RESERVE,
                        canonicalPath = canonicalPath,
                        contractVersion = contractVersion,
                        openapiDigest = "b".repeat(64),
                        requestHash = "c".repeat(64),
                    ),
                )
        sales[quadruple.operationId] = pending
        return try {
            val receipt =
                inventoryPort.reserve(
                    ReserveInventoryCommand(
                        quadruple = quadruple,
                        catalogVersion = catalogPort.currentSnapshot()?.version ?: "unknown",
                        lines = lines,
                    ),
                )
            val reserved =
                pending.markReserved(
                    RemoteEvidence(
                        reservationRef = receipt.reservationRef ?: error("fixture receipt missing ref"),
                        receipt = receipt.receipt ?: error("fixture receipt missing receipt"),
                        contractVersion = receipt.contract.contractVersion,
                        openapiDigest = receipt.contract.openapiDigestSha256 ?: "b".repeat(64),
                        acceptedPriceVersions = receipt.acceptedPriceVersions,
                        expiresAt = receipt.expiresAt,
                    ),
                )
            sales[quadruple.operationId] = reserved
            saleRecordStore.recordReserved(reserved)
            reserved
        } catch (ex: IllegalStateException) {
            pending
        }
    }

    fun commit(operationId: String, now: Instant = Instant.now()): SaleSaga {
        val current = sales[operationId] ?: throw IllegalArgumentException("sale $operationId is not in this process")
        if (current.status == SaleStatus.COMMITTED) return current
        fiscalPolicy.assertCanCommit(
            CompanionEnvironment.valueOf(environmentName),
            current.fiscalStatus,
            authorization = null,
            now = now,
        )
        retiredPolicy.assertCanPost(current.retired || retirementPort.isRetired(operationId))
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
        inventoryPort.commit(
            CommitInventoryCommand(
                quadruple = pending.quadruple,
                reservationRef = pending.evidence?.reservationRef ?: error("reservation evidence is missing"),
            ),
        )
        val committed = pending.markCommitted()
        saleRecordStore.recordCommitted(committed)
        sales[operationId] = committed
        return committed
    }

    fun release(operationId: String): SaleSaga {
        val current = sales[operationId] ?: throw IllegalArgumentException("sale $operationId is not in this process")
        if (current.status == SaleStatus.RELEASED) return current
        if (current.status == SaleStatus.COMMITTED) {
            throw IllegalArgumentException("a committed sale is not released")
        }
        retiredPolicy.assertCanPost(current.retired || retirementPort.isRetired(operationId))
        val pending =
            when (current.status) {
                SaleStatus.RESERVED, SaleStatus.PAYMENT_CAPTURED ->
                    current.withCommand(command(current, StoreCoreOperationKind.RELEASE)).markReleasePending()
                SaleStatus.RELEASE_PENDING -> current
                else -> throw IllegalArgumentException("sale ${current.status} cannot release")
            }
        sales[operationId] = pending
        saleRecordStore.recordReleasePending(pending)
        inventoryPort.release(
            ReleaseInventoryCommand(
                quadruple = pending.quadruple,
                reservationRef = pending.evidence?.reservationRef ?: error("reservation evidence is missing"),
            ),
        )
        val released = pending.markReleased()
        saleRecordStore.recordReleased(released)
        sales[operationId] = released
        return released
    }

    private fun command(saga: SaleSaga, kind: StoreCoreOperationKind): OutboxCommand {
        val reserve = saga.outbox.first { it.kind == StoreCoreOperationKind.RESERVE }
        return reserve.copy(kind = kind)
    }

    fun retire(operationId: String) {
        retirementPort.markRetired(operationId)
        sales[operationId]?.let { sales[operationId] = it.markRetired() }
    }

    fun stored(operationId: String): SaleSaga? = sales[operationId]
}
