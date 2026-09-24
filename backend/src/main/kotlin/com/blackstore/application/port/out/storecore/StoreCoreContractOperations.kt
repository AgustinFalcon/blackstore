package com.blackstore.application.port.out.storecore

import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.model.StoreCoreOperationReceipt
import com.blackstore.domain.port.out.storecore.CatalogSnapshot
import com.blackstore.domain.port.out.storecore.CommitInventoryCommand
import com.blackstore.domain.port.out.storecore.ReconcileProjection
import com.blackstore.domain.port.out.storecore.ReconcileQuery
import com.blackstore.domain.port.out.storecore.ReleaseInventoryCommand
import com.blackstore.domain.port.out.storecore.ReserveInventoryCommand

/**
 * Application-facing StoreCore operations. HTTP-free, DB-free, secret-free.
 * Infrastructure adapters (fixture, blocked, later localhost HTTP) implement the domain ports;
 * this interface is the six-operation contract the application may orchestrate.
 */
interface StoreCoreContractOperations {
    fun catalog(): CatalogSnapshot?

    fun reserve(command: ReserveInventoryCommand): StoreCoreOperationReceipt

    fun commit(command: CommitInventoryCommand): StoreCoreOperationReceipt

    fun release(command: ReleaseInventoryCommand): StoreCoreOperationReceipt

    fun get(quadruple: OperationQuadruple): StoreCoreOperationReceipt?

    fun reconcile(query: ReconcileQuery): ReconcileProjection
}
