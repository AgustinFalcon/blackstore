package com.blackstore.infrastructure.storecore

import com.blackstore.domain.exception.BlockedStoreCoreIntegrationException
import com.blackstore.domain.port.out.storecore.CatalogSnapshot
import com.blackstore.domain.port.out.storecore.StoreCoreCatalogPort
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    name = ["blackstore.storecore.integration.mode"],
    havingValue = "blocked",
)
class BlockedStoreCoreCatalogAdapter : StoreCoreCatalogPort {

    override fun currentSnapshot(): CatalogSnapshot? = throw BlockedStoreCoreIntegrationException()
}
