package com.blackstore.infrastructure.storecore

import com.blackstore.domain.exception.BlockedStoreCoreIntegrationException
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.model.StoreCoreOperationReceipt
import com.blackstore.domain.port.out.storecore.CommitInventoryCommand
import com.blackstore.domain.port.out.storecore.ReleaseInventoryCommand
import com.blackstore.domain.port.out.storecore.ReserveInventoryCommand
import com.blackstore.domain.port.out.storecore.StoreCoreInventoryPort
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Default outbound adapter while real StoreCore HTTP remains blocked.
 */
@Component
@ConditionalOnProperty(
    name = ["blackstore.storecore.integration.mode"],
    havingValue = "blocked",
)
class BlockedStoreCoreInventoryAdapter : StoreCoreInventoryPort {

    override fun reserve(command: ReserveInventoryCommand): StoreCoreOperationReceipt =
        throw blocked()

    override fun commit(command: CommitInventoryCommand): StoreCoreOperationReceipt =
        throw blocked()

    override fun release(command: ReleaseInventoryCommand): StoreCoreOperationReceipt =
        throw blocked()

    override fun getOperation(quadruple: OperationQuadruple): StoreCoreOperationReceipt? =
        throw blocked()

    private fun blocked(): BlockedStoreCoreIntegrationException =
        BlockedStoreCoreIntegrationException()
}
