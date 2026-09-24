package com.blackstore.application.storecore

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Binds `blackstore.storecore` from YAML. Defaults stay fail-closed.
 * Token refs are names, never secret values.
 */
@ConfigurationProperties(prefix = "blackstore.storecore")
data class StoreCoreIntegrationProperties(
    val integration: Integration = Integration(),
    val transport: Transport = Transport(),
    val control: Control = Control(),
    val contract: Contract = Contract(),
) {
    fun toSettings(): StoreCoreTransportSettings =
        StoreCoreTransportSettings(
            enabled = integration.enabled,
            killSwitch = transport.killSwitch || control.killSwitch,
            capabilityActive = control.capabilityActive,
            baseUrl = transport.baseUrl,
            tlsRequired = transport.tlsRequired,
            allowPlainLoopback = transport.allowPlainLoopback,
            identityRef = transport.identityRef.ifBlank { control.identityRef },
            tokenRef = transport.tokenRef.ifBlank { control.tokenRef },
            timeoutMs = transport.timeoutMs,
            maxRetries = transport.maxRetries,
            path = contract.canonicalPath,
            version = contract.version,
            digest = contract.sha256,
        )

    data class Integration(
        val enabled: Boolean = false,
        val mode: String = "fixture",
    )

    data class Transport(
        val killSwitch: Boolean = true,
        val tlsRequired: Boolean = true,
        val allowPlainLoopback: Boolean = false,
        val baseUrl: String = "",
        val identityRef: String = "",
        val tokenRef: String = "",
        val clientInstanceId: String = "",
        val timeoutMs: Int = 2000,
        val maxRetries: Int = 2,
    )

    data class Control(
        val capabilityActive: Boolean = false,
        val killSwitch: Boolean = true,
        val identityRef: String = "",
        val tokenRef: String = "",
        val canaryPercent: Int = 0,
        val rolloutEnabled: Boolean = false,
    )

    data class Contract(
        val canonicalPath: String = "",
        val version: String = "",
        val sha256: String = "",
    )

    companion object {
        const val LOCAL_LOOPBACK_TOKEN_REF = "local-loopback-token"
        const val LOCAL_LOOPBACK_TOKEN = "local-loopback"
    }
}
