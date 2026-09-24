package com.blackstore.application.storecore

object StoreCoreTransportTelemetry {
    fun line(
        kind: String,
        digest: String,
        outcome: String,
        retries: Int,
        operationId: String,
        identityRef: String,
    ): String =
        "storecore_transport kind=$kind digest=${digest.take(8)} outcome=$outcome retries=$retries operationId=$operationId identityRef=$identityRef"

    fun containsSecret(line: String, token: String): Boolean {
        val lower = line.lowercase()
        return lower.contains("bearer") ||
            lower.contains("authorization") ||
            (token.isNotBlank() && line.contains(token))
    }
}
