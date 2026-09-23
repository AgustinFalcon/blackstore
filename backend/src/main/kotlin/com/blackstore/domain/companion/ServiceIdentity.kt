package com.blackstore.domain.companion

/**
 * BlackStore service identity. Opaque client reference bound to one installation.
 * This is not a StoreCore credential, token, or secret.
 */
data class ServiceIdentity(
    val clientRef: String,
) {
    init {
        require(clientRef.isNotBlank()) { "service client ref is required" }
        require(clientRef.length <= 160) { "service client ref exceeds 160 characters" }
    }
}
