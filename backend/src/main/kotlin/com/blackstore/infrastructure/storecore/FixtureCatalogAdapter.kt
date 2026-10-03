package com.blackstore.infrastructure.storecore

import com.blackstore.domain.model.StoreCoreContractRef
import com.blackstore.domain.port.out.storecore.CatalogItem
import com.blackstore.domain.port.out.storecore.CatalogSnapshot
import com.blackstore.domain.port.out.storecore.StoreCoreCatalogPort
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.math.BigDecimal
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
                    CatalogItem("SKU-1", "Café molido 500 g", "variant-1", "price-demo-1", BigDecimal("8490.00")),
                    CatalogItem("SKU-YERBA-1K", "Yerba mate 1 kg", "variant-yerba-1k", "price-demo-1", BigDecimal("6790.00")),
                    CatalogItem("SKU-AZUCAR-1K", "Azúcar 1 kg", "variant-azucar-1k", "price-demo-1", BigDecimal("1890.00")),
                    CatalogItem("SKU-LECHE-1L", "Leche entera 1 l", "variant-leche-1l", "price-demo-1", BigDecimal("2150.00")),
                    CatalogItem("SKU-GALLE-300", "Galletitas 300 g", "variant-galle-300", "price-demo-1", BigDecimal("3290.00")),
                ),
        )

    override fun currentSnapshot(): CatalogSnapshot? {
        if (!available) return null
        return snapshot.copy(stale = stale)
    }
}
