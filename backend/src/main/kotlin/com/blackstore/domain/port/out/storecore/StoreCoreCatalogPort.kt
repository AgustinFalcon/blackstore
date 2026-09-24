package com.blackstore.domain.port.out.storecore

import com.blackstore.domain.model.StoreCoreContractRef
import java.time.Instant

/**
 * Read-only catalog projection from StoreCore (fixture until TASK-005).
 */
interface StoreCoreCatalogPort {

    fun currentSnapshot(): CatalogSnapshot?
}

data class CatalogSnapshot(
    val version: String,
    val importedAt: Instant,
    val validUntil: Instant?,
    val contract: StoreCoreContractRef,
    val stale: Boolean,
    val items: List<CatalogItem> = emptyList(),
)

data class CatalogItem(
    val sku: String,
    val name: String,
    val variantId: String,
)
