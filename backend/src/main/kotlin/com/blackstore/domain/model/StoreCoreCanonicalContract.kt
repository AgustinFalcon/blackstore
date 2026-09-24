package com.blackstore.domain.model

object StoreCoreCanonicalContract {
    const val CANONICAL_PATH = "/blackstore-integration/v1"
    const val VERSION = "1.0.0-draft"
    const val SHA256 = "7b907a2e11c52a66b7253407fb3f9450cae7b792beccf34c1636be9d3945de30"

    fun assertCompatible(path: String, version: String, digest: String) {
        val normalized = digest.trim().lowercase()
        if (path != CANONICAL_PATH || version != VERSION || normalized != SHA256) {
            throw IllegalStateException("STORECORE_CONTRACT_INCOMPATIBLE")
        }
    }
}
