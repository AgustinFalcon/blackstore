package com.blackstore.connector

import com.blackstore.domain.model.StoreCoreContractRef
import com.blackstore.domain.port.out.storecore.CatalogItem
import com.blackstore.domain.port.out.storecore.CatalogSnapshot
import com.blackstore.domain.port.out.storecore.CatalogSnapshotStore
import com.blackstore.domain.port.out.storecore.CatalogSource
import com.blackstore.domain.port.out.storecore.StoreCoreCatalogPort
import com.blackstore.infrastructure.storecore.DurableCatalogAdapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class DurableCatalogAdapterTest {
    private val now = Instant.parse("2026-11-12T12:00:00Z")
    private val contract = StoreCoreContractRef("/blackstore-integration/v1", "1.0.0-draft", "digest")

    @Test
    fun persistsValidLiveAndRecoversItAsExplicitCache() {
        val store = MemoryStore()
        val live = snapshot(now.plusSeconds(60))
        assertEquals(CatalogSource.STORECORE_LIVE, DurableCatalogAdapter(Remote(live), store, contract, clock = { now }).currentSnapshot()?.source)
        val recovered = DurableCatalogAdapter(Remote(null), store, contract, clock = { now.plusSeconds(30) }).currentSnapshot()
        requireNotNull(recovered)
        assertEquals(CatalogSource.STORECORE_CACHE, recovered.source)
        assertFalse(recovered.stale)
        assertEquals("price-1", recovered.items.single().priceVersion)
    }

    @Test
    fun expiredCacheIsVisibleButFailClosedWhenRemoteIsUnavailable() {
        val store = MemoryStore(snapshot(now.plusSeconds(10)))
        val recovered = DurableCatalogAdapter(Remote(null), store, contract, clock = { now.plusSeconds(20) }).currentSnapshot()
        requireNotNull(recovered)
        assertTrue(recovered.stale)
        assertEquals(CatalogSource.STORECORE_CACHE, recovered.source)
        assertEquals(1, store.replacements)
    }

    @Test
    fun invalidOrIncompatibleRemoteNeverFallsBackToACompatibleCache() {
        val compatible = MemoryStore(snapshot(now.plusSeconds(60)))
        val invalid = snapshot(now.plusSeconds(60)).copy(source = CatalogSource.UNKNOWN)
        assertNull(DurableCatalogAdapter(Remote(invalid), compatible, contract, clock = { now }).currentSnapshot())
        val foreign = snapshot(now.plusSeconds(60)).copy(contract = contract.copy(openapiDigestSha256 = "foreign"))
        assertNull(DurableCatalogAdapter(Remote(foreign), compatible, contract, clock = { now }).currentSnapshot())
    }

    @Test
    fun noRemoteAndNoCacheRemainUnavailable() {
        assertNull(DurableCatalogAdapter(Remote(null), MemoryStore(), contract, clock = { now }).currentSnapshot())
    }

    @Test
    fun incompatibleRemoteAndCacheContractFailClosed() {
        val foreign = snapshot(now.plusSeconds(60)).copy(contract = contract.copy(openapiDigestSha256 = "foreign"))
        assertNull(DurableCatalogAdapter(Remote(foreign), MemoryStore(foreign), contract, clock = { now }).currentSnapshot())
    }

    @Test
    fun cachedSnapshotWithoutExpiryIsAlwaysStale() {
        val noExpiry = snapshot(now.plusSeconds(60)).copy(validUntil = null)
        val recovered = DurableCatalogAdapter(Remote(null), MemoryStore(noExpiry), contract, clock = { now }).currentSnapshot()
        requireNotNull(recovered)
        assertTrue(recovered.stale)
        assertTrue(recovered.v2Stale)
    }

    @Test
    fun cacheWriteFailureDoesNotMislabelOrHideAValidatedLiveSnapshot() {
        val live = snapshot(now.plusSeconds(60))
        val result = DurableCatalogAdapter(Remote(live), FailingStore, contract, clock = { now }).currentSnapshot()
        assertEquals(CatalogSource.STORECORE_LIVE, result?.source)
    }

    private fun snapshot(validUntil: Instant) = CatalogSnapshot(
        "catalog-1", now, validUntil, contract, false,
        listOf(CatalogItem("SKU-1", "Taladro", "variant-1", "price-1", BigDecimal("1000.00"))),
        source = CatalogSource.STORECORE_LIVE,
    )

    private class Remote(private val snapshot: CatalogSnapshot?) : StoreCoreCatalogPort {
        override fun currentSnapshot() = snapshot
    }

    private class MemoryStore(initial: CatalogSnapshot? = null) : CatalogSnapshotStore {
        private var snapshot = initial
        var replacements = if (initial == null) 0 else 1
        override fun replace(snapshot: CatalogSnapshot) { this.snapshot = snapshot; replacements++ }
        override fun latest() = snapshot
    }

    private object FailingStore : CatalogSnapshotStore {
        override fun replace(snapshot: CatalogSnapshot) = error("database unavailable")
        override fun latest(): CatalogSnapshot? = null
    }
}
