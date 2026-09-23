package com.blackstore.domain.model

/**
 * Pinned StoreCore contract metadata. Digest is populated at runtime from the canonical YAML (adapter WIP).
 */
data class StoreCoreContractRef(
    val canonicalPath: String,
    val contractVersion: String,
    val openapiDigestSha256: String? = null,
) {
    init {
        require(canonicalPath.startsWith("/")) { "canonicalPath must be absolute" }
        require(contractVersion.isNotBlank()) { "contractVersion is required" }
    }
}
