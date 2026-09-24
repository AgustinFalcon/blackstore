package com.blackstore.application.port.out.storecore

import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.model.StoreCoreOperationReceipt
import com.blackstore.domain.port.out.storecore.CatalogSnapshot
import com.blackstore.domain.port.out.storecore.CommitInventoryCommand
import com.blackstore.domain.port.out.storecore.ReconcileProjection
import com.blackstore.domain.port.out.storecore.ReconcileQuery
import com.blackstore.domain.port.out.storecore.ReleaseInventoryCommand
import com.blackstore.domain.port.out.storecore.ReserveInventoryCommand
import com.blackstore.domain.port.out.storecore.StoreCoreCatalogPort
import com.blackstore.domain.port.out.storecore.StoreCoreInventoryPort
import com.blackstore.domain.port.out.storecore.StoreCoreReconcilePort

class StoreCoreContractFacade(
    private val catalogPort: StoreCoreCatalogPort,
    private val inventoryPort: StoreCoreInventoryPort,
    private val reconcilePort: StoreCoreReconcilePort,
) : StoreCoreContractOperations {
    override fun catalog(): CatalogSnapshot? = catalogPort.currentSnapshot()

    override fun reserve(command: ReserveInventoryCommand): StoreCoreOperationReceipt = inventoryPort.reserve(command)

    override fun commit(command: CommitInventoryCommand): StoreCoreOperationReceipt = inventoryPort.commit(command)

    override fun release(command: ReleaseInventoryCommand): StoreCoreOperationReceipt = inventoryPort.release(command)

    override fun get(quadruple: OperationQuadruple): StoreCoreOperationReceipt? = inventoryPort.getOperation(quadruple)

    override fun reconcile(query: ReconcileQuery): ReconcileProjection = reconcilePort.reconcile(query)
}
