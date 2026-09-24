package com.blackstore.infrastructure.storecore

import com.blackstore.domain.model.StoreCoreContractRef
import com.blackstore.domain.port.out.storecore.CatalogItem
import com.blackstore.domain.port.out.storecore.CatalogSnapshot
import com.blackstore.domain.port.out.storecore.StoreCoreCatalogPort
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Instant

@Component
@ConditionalOnProperty(
    name = ["blackstore.storecore.integration.mode"],
    havingValue = "fixture",
    matchIfMissing = true,
)
class FixtureCatalogAdapter(
    @Value("\${blackstore.storecore.contract.canonical-path}") canonicalPath: String,
    @Value("\${blackstore.storecore.contract.version}") contractVersion: String,
) : StoreCoreCatalogPort {

    var stale: Boolean = false
    var available: Boolean = true

    private val snapshot =
        CatalogSnapshot(
            version = "fixture-v1",
            importedAt = Instant.parse("2026-09-22T00:00:00Z"),
            validUntil = Instant.parse("2026-12-31T00:00:00Z"),
            contract = StoreCoreContractRef(canonicalPath, contractVersion),
            stale = false,
            items =
                listOf(
                    CatalogItem(sku = "SKU-1", name = "Cafe", variantId = "variant-1"),
                ),
        )

    override fun currentSnapshot(): CatalogSnapshot? {
        if (!available) return null
        return snapshot.copy(stale = stale)
    }
}
