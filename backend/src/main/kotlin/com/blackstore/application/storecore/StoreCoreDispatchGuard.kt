package com.blackstore.application.storecore

import com.blackstore.domain.exception.BlockedStoreCoreIntegrationException
import com.blackstore.domain.model.StoreCoreCanonicalContract
import java.net.URI

object StoreCoreDispatchGuard {
    fun assertCanDispatch(settings: StoreCoreTransportSettings) {
        StoreCoreCanonicalContract.assertCompatible(settings.path, settings.version, settings.digest)
        if (!settings.enabled || settings.killSwitch || !settings.capabilityActive) {
            throw BlockedStoreCoreIntegrationException("STORECORE_TRANSPORT_DISABLED")
        }
        if (settings.identityRef.isBlank() || settings.tokenRef.isBlank()) {
            throw BlockedStoreCoreIntegrationException("STORECORE_IDENTITY_MISSING")
        }
        if (settings.baseUrl.isBlank()) {
            throw BlockedStoreCoreIntegrationException("STORECORE_ENDPOINT_MISSING")
        }
        val uri = URI(settings.baseUrl)
        val host = uri.host?.lowercase() ?: ""
        val loopback = host == "127.0.0.1" || host == "localhost" || host == "[::1]" || host == "::1"
        val https = uri.scheme.equals("https", ignoreCase = true)
        if (!https) {
            if (settings.tlsRequired) {
                throw BlockedStoreCoreIntegrationException("STORECORE_TLS_REQUIRED")
            }
            if (!loopback || !settings.allowPlainLoopback) {
                throw BlockedStoreCoreIntegrationException("STORECORE_PLAINTEXT_FORBIDDEN")
            }
        }
    }
}
