package com.blackstore.domain.exception

/**
 * Raised when code paths require the real StoreCore HTTP adapter, which remains blocked
 * until DEFERRED-STORECORE-CONNECTOR-001 exit conditions are met.
 */
class BlockedStoreCoreIntegrationException(
    message: String = "StoreCore HTTP integration is not authorized. Use fixtures only until Sol adapter GO.",
) : RuntimeException(message)
