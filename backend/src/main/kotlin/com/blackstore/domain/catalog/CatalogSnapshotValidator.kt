package com.blackstore.domain.catalog

import com.blackstore.domain.port.out.storecore.CatalogSnapshot
import com.blackstore.domain.port.out.storecore.CatalogSource
import com.blackstore.domain.sales.MoneyPolicy

class CatalogSnapshotValidator {
    fun isPersistable(snapshot: CatalogSnapshot): Boolean {
        if (snapshot.source != CatalogSource.STORECORE_LIVE || snapshot.stale) return false
        if (snapshot.version.isBlank() || snapshot.contract.canonicalPath.isBlank() || snapshot.contract.contractVersion.isBlank()) return false
        if (snapshot.items.isEmpty() || snapshot.v2Items.isEmpty() || snapshot.validUntil?.isAfter(snapshot.importedAt) != true) return false
        return validItems(snapshot.items) && validItems(snapshot.v2Items)
    }

    private fun validItems(items: List<com.blackstore.domain.port.out.storecore.CatalogItem>): Boolean {
        if (items.map { it.sku }.toSet().size != items.size || items.map { it.variantId }.toSet().size != items.size) return false
        return items.all {
            it.sku.isNotBlank() && it.name.isNotBlank() && it.variantId.isNotBlank() &&
                it.rawSku.isNotEmpty() && it.rawName.isNotEmpty() && it.rawVariantId.isNotEmpty() &&
                (it.unitPrice == null || (it.unitPrice.signum() >= 0 && MoneyPolicy.valid(it.unitPrice)))
        }
    }
}
