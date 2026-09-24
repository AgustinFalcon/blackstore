package com.blackstore.domain.model

/**
 * Contract identity carried on receipts. Digest is the pinned SHA-256 string, not a YAML parse.
 * Durable receipts require a non-blank digest; PENDING may omit it.
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
