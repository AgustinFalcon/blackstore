package com.blackstore.domain.port.out.storecore

import com.blackstore.domain.model.StoreCoreContractRef
import java.math.BigDecimal
import java.time.Instant

/**
 * Read-only catalog projection from StoreCore (fixture until TASK-005).
 */
interface StoreCoreCatalogPort {

    fun currentSnapshot(): CatalogSnapshot?
}

enum class CatalogSource {
    STORECORE_LIVE,
    STORECORE_CACHE,
    LOCAL_FIXTURE,
    UNAVAILABLE,
    UNKNOWN,
    ;

    companion object {
        fun fromWire(raw: String?): CatalogSource = entries.firstOrNull { it != UNKNOWN && it.name == raw } ?: UNKNOWN
    }
}

data class CatalogSnapshot(
    val version: String,
    val importedAt: Instant,
    val validUntil: Instant?,
    val contract: StoreCoreContractRef,
    val stale: Boolean,
    val items: List<CatalogItem> = emptyList(),
    val v2Items: List<CatalogItem> = items,
    val v2Stale: Boolean = stale,
    val source: CatalogSource = CatalogSource.UNKNOWN,
)

data class CatalogItem(
    val sku: String,
    val name: String,
    val variantId: String,
    val priceVersion: String? = null,
    val unitPrice: BigDecimal? = null,
    val rawSku: String = sku,
    val rawName: String = name,
    val rawVariantId: String = variantId,
    val rawPriceVersion: String? = priceVersion,
)

interface CatalogSnapshotStore {
    fun replace(snapshot: CatalogSnapshot)

    fun latest(): CatalogSnapshot?
}
