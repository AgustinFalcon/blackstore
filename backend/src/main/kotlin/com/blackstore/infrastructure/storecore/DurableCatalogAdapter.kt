package com.blackstore.infrastructure.storecore

import com.blackstore.domain.catalog.CatalogSnapshotValidator
import com.blackstore.domain.model.StoreCoreContractRef
import com.blackstore.domain.port.out.storecore.CatalogSnapshot
import com.blackstore.domain.port.out.storecore.CatalogSnapshotStore
import com.blackstore.domain.port.out.storecore.CatalogSource
import com.blackstore.domain.port.out.storecore.StoreCoreCatalogPort
import org.slf4j.LoggerFactory
import java.time.Instant

class DurableCatalogAdapter(
    private val remote: StoreCoreCatalogPort,
    private val store: CatalogSnapshotStore,
    private val expectedContract: StoreCoreContractRef,
    private val validator: CatalogSnapshotValidator = CatalogSnapshotValidator(),
    private val clock: () -> Instant = Instant::now,
) : StoreCoreCatalogPort {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun currentSnapshot(): CatalogSnapshot? {
        val remoteSnapshot = runCatching { remote.currentSnapshot() }.getOrNull()
        if (remoteSnapshot != null) {
            if (remoteSnapshot.contract != expectedContract || !validator.isPersistable(remoteSnapshot)) return null
            runCatching { store.replace(remoteSnapshot) }
                .onFailure { logger.warn("StoreCore catalog cache write failed; serving validated live snapshot") }
            return remoteSnapshot
        }
        val cached = runCatching { store.latest() }.getOrNull()?.takeIf { it.contract == expectedContract } ?: return null
        val now = clock()
        val cacheStale = cached.items.isEmpty() || cached.v2Items.isEmpty() || cached.validUntil?.isAfter(now) != true
        return cached.copy(
            source = CatalogSource.STORECORE_CACHE,
            stale = cacheStale,
            v2Stale = cacheStale,
        )
    }
}
