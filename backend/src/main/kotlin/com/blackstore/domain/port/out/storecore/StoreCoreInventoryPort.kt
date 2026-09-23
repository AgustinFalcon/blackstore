package com.blackstore.domain.port.out.storecore

import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.model.StoreCoreOperationReceipt

/**
 * Outbound port for StoreCore inventory saga (reserve / commit / release / GET).
 * Infrastructure must not expose HTTP details to domain or application layers.
 * Real HTTP adapter is blocked by DEFERRED-STORECORE-CONNECTOR-001.
 */
interface StoreCoreInventoryPort {

    fun reserve(command: ReserveInventoryCommand): StoreCoreOperationReceipt

    fun commit(command: CommitInventoryCommand): StoreCoreOperationReceipt

    fun release(command: ReleaseInventoryCommand): StoreCoreOperationReceipt

    fun getOperation(quadruple: OperationQuadruple): StoreCoreOperationReceipt?
}

data class ReserveInventoryCommand(
    val quadruple: OperationQuadruple,
    val catalogVersion: String,
    val lines: List<ReserveLineCommand>,
)

data class ReserveLineCommand(
    val variantId: String,
    val quantity: Int,
    val expectedPriceVersion: String,
)

data class CommitInventoryCommand(
    val quadruple: OperationQuadruple,
    val reservationRef: String,
)

data class ReleaseInventoryCommand(
    val quadruple: OperationQuadruple,
    val reservationRef: String,
)
