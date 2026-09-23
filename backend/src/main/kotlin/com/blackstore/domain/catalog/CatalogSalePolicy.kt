package com.blackstore.domain.catalog

import com.blackstore.domain.exception.ForbiddenOperationException
import com.blackstore.domain.port.out.storecore.CatalogSnapshot
import java.time.Instant

class CatalogSalePolicy {

    fun assertSaleAllowed(
        snapshot: CatalogSnapshot?,
        now: Instant,
    ) {
        if (snapshot == null || snapshot.stale) {
            throw ForbiddenOperationException("catalog is unavailable; sale transition is blocked and catalog stays read-only")
        }
        if (snapshot.validUntil != null && !snapshot.validUntil.isAfter(now)) {
            throw ForbiddenOperationException("catalog version ${snapshot.version} is past valid-until")
        }
    }
}
