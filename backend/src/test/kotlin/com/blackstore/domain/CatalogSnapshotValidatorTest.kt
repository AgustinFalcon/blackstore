package com.blackstore.domain

import com.blackstore.domain.catalog.CatalogSnapshotValidator
import com.blackstore.domain.model.StoreCoreContractRef
import com.blackstore.domain.port.out.storecore.CatalogItem
import com.blackstore.domain.port.out.storecore.CatalogSnapshot
import com.blackstore.domain.port.out.storecore.CatalogSource
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class CatalogSnapshotValidatorTest {
    private val validator = CatalogSnapshotValidator()
    private val imported = Instant.parse("2026-11-12T12:00:00Z")

    @Test
    fun acceptsOnlyFreshLiveSnapshotsWithUniqueClosedIdentity() {
        val valid = snapshot()
        assertTrue(validator.isPersistable(valid))
        assertFalse(validator.isPersistable(valid.copy(source = CatalogSource.STORECORE_CACHE)))
        assertFalse(validator.isPersistable(valid.copy(stale = true)))
        assertFalse(validator.isPersistable(valid.copy(items = emptyList())))
        assertFalse(validator.isPersistable(valid.copy(items = listOf(valid.items[0], valid.items[0].copy(name = "Otro")))))
        assertFalse(validator.isPersistable(valid.copy(items = listOf(valid.items[0].copy(unitPrice = BigDecimal("-1"))))))
        assertFalse(validator.isPersistable(valid.copy(validUntil = imported)))
        assertFalse(validator.isPersistable(valid.copy(validUntil = null)))
    }

    private fun snapshot() = CatalogSnapshot(
        version = "catalog-1",
        importedAt = imported,
        validUntil = imported.plusSeconds(600),
        contract = StoreCoreContractRef("/blackstore-integration/v1", "1.0.0-draft", "digest"),
        stale = false,
        source = CatalogSource.STORECORE_LIVE,
        items = listOf(CatalogItem("SKU-1", "Taladro", "variant-1", "price-1", BigDecimal("1000.00"))),
    )
}
