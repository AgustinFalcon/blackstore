package com.blackstore.infrastructure.persistence

import com.blackstore.domain.model.StoreCoreContractRef
import com.blackstore.domain.port.out.storecore.CatalogItem
import com.blackstore.domain.port.out.storecore.CatalogSnapshot
import com.blackstore.domain.port.out.storecore.CatalogSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class JdbcCatalogSnapshotStorePostgresTest {
    @Test
    fun cleanMigrationPersistsReplacementAndSurvivesStoreRecreation() = database { source ->
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate()
        val firstStore = JdbcCatalogSnapshotStore(source)
        assertNull(firstStore.latest())
        firstStore.replace(snapshot("catalog-1", "SKU-1", "variant-1"))

        val afterRestart = JdbcCatalogSnapshotStore(source).latest()
        requireNotNull(afterRestart)
        assertEquals(CatalogSource.STORECORE_CACHE, afterRestart.source)
        assertEquals("catalog-1", afterRestart.version)
        assertEquals("price-1", afterRestart.items.single().priceVersion)
        assertEquals(" raw-v2 ", afterRestart.v2Items.single().rawSku)

        JdbcCatalogSnapshotStore(source).replace(snapshot("catalog-2", "SKU-2", "variant-2"))
        val replaced = firstStore.latest()
        requireNotNull(replaced)
        assertEquals("catalog-2", replaced.version)
        assertEquals(listOf("SKU-2"), replaced.items.map { it.sku })
    }

    @Test
    fun v13UpgradeCreatesAnEmptyCacheWithoutInventingCatalogEvidence() = database { source ->
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("13").load().migrate()
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate()
        val store = JdbcCatalogSnapshotStore(source)
        assertNull(store.latest())
        store.replace(snapshot("catalog-after-upgrade", "SKU-U", "variant-u"))
        assertEquals("catalog-after-upgrade", store.latest()?.version)
    }

    @Test
    fun concurrentReplacementNeverMixesHeaderLegacyAndV2Projections() = database { source ->
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate()
        val store = JdbcCatalogSnapshotStore(source)
        store.replace(snapshot("catalog-0", "SKU-0", "variant-0"))
        val pool = Executors.newFixedThreadPool(2)
        try {
            val writer = pool.submit {
                (1..40).forEach { store.replace(snapshot("catalog-$it", "SKU-$it", "variant-$it")) }
            }
            val reader = pool.submit {
                repeat(100) {
                    val current = requireNotNull(store.latest())
                    val suffix = current.version.removePrefix("catalog-")
                    assertEquals("SKU-$suffix", current.items.single().sku)
                    assertEquals("SKU-$suffix", current.v2Items.single().sku)
                }
            }
            writer.get(30, TimeUnit.SECONDS)
            reader.get(30, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun snapshot(version: String, sku: String, variant: String) = CatalogSnapshot(
        version = version,
        importedAt = Instant.parse("2026-11-12T12:00:00Z"),
        validUntil = Instant.parse("2026-11-12T13:00:00Z"),
        contract = StoreCoreContractRef("/blackstore-integration/v1", "1.0.0-draft", "digest"),
        stale = false,
        items = listOf(CatalogItem(sku, "Taladro", variant, "price-1", BigDecimal("1000.00"))),
        v2Items = listOf(CatalogItem(sku, "Taladro", variant, "price-1", BigDecimal("1000.00"), " raw-v2 ", "Taladro", variant, "price-1")),
        source = CatalogSource.STORECORE_LIVE,
    )

    private fun database(block: (PGSimpleDataSource) -> Unit) {
        PostgreSQLContainer<Nothing>("postgres:16-alpine").use { postgres ->
            postgres.start()
            val source = PGSimpleDataSource().also {
                it.setURL(postgres.jdbcUrl)
                it.user = postgres.username
                it.password = postgres.password
            }
            block(source)
        }
    }
}
