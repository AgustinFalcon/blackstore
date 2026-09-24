package com.blackstore.application.storecore

/**
 * Transport settings. Token and identity are opaque references, never secret values.
 */
data class StoreCoreTransportSettings(
    val enabled: Boolean,
    val killSwitch: Boolean,
    val capabilityActive: Boolean,
    val baseUrl: String,
    val tlsRequired: Boolean,
    val allowPlainLoopback: Boolean,
    val identityRef: String,
    val tokenRef: String,
    val timeoutMs: Int,
    val maxRetries: Int,
    val path: String,
    val version: String,
    val digest: String,
)
