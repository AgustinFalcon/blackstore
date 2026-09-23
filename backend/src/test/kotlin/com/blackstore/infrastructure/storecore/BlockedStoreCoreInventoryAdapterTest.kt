package com.blackstore.infrastructure.storecore

import com.blackstore.domain.exception.BlockedStoreCoreIntegrationException
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.port.out.storecore.ReserveInventoryCommand
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BlockedStoreCoreInventoryAdapterTest {

    private val adapter = BlockedStoreCoreInventoryAdapter()

    @Test
    fun reserveFailsClosedWithoutHttp() {
        assertThrows<BlockedStoreCoreIntegrationException> {
            adapter.reserve(
                ReserveInventoryCommand(
                    quadruple =
                        OperationQuadruple(
                            clientInstanceId = "ci-1",
                            deviceId = "dev-1",
                            saleId = "sale-1",
                            operationId = "op-1",
                        ),
                    catalogVersion = "v1",
                    lines = emptyList(),
                ),
            )
        }
    }
}
