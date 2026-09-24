package com.blackstore.application.storecore

import com.blackstore.domain.exception.BlockedStoreCoreIntegrationException
import com.blackstore.domain.model.StoreCoreCanonicalContract

data class StoreCoreControlEvent(
    val actorRef: String,
    val event: String,
    val reason: String,
    val identityRef: String,
)

/**
 * Fail-closed lifecycle. Token/identity are opaque refs. Live rollout stays off.
 */
class StoreCoreControlPlane(
    private var capabilityActive: Boolean = false,
    private var killSwitch: Boolean = true,
    private var identityRef: String = "",
    private var tokenRef: String = "",
    private var canaryPercent: Int = 0,
    private var rolloutEnabled: Boolean = false,
    private var previousIdentityRef: String = "",
    private var previousTokenRef: String = "",
) {
    val audit = mutableListOf<StoreCoreControlEvent>()

    fun currentIdentityRef(): String = identityRef

    fun rotate(newIdentityRef: String, newTokenRef: String, actorRef: String, reason: String) {
        require(newIdentityRef.isNotBlank() && newTokenRef.isNotBlank())
        require(!newIdentityRef.contains("Bearer", ignoreCase = true))
        require(!newTokenRef.contains("Bearer", ignoreCase = true))
        require(reason.length >= 3)
        previousIdentityRef = identityRef
        previousTokenRef = tokenRef
        identityRef = newIdentityRef
        tokenRef = newTokenRef
        audit += StoreCoreControlEvent(actorRef, "ROTATION", reason, newIdentityRef)
    }

    fun disableCapability(actorRef: String, reason: String) {
        capabilityActive = false
        audit += StoreCoreControlEvent(actorRef, "CAPABILITY_DISABLED", reason, identityRef)
    }

    fun engageKillSwitch(actorRef: String, reason: String) {
        killSwitch = true
        audit += StoreCoreControlEvent(actorRef, "KILL_SWITCH", reason, identityRef)
    }

    fun rollback(actorRef: String, reason: String) {
        if (previousIdentityRef.isNotBlank()) {
            identityRef = previousIdentityRef
            tokenRef = previousTokenRef
        }
        killSwitch = true
        capabilityActive = false
        canaryPercent = 0
        rolloutEnabled = false
        audit += StoreCoreControlEvent(actorRef, "ROLLBACK", reason, identityRef)
    }

    fun transportSettings(loopback: StoreCoreTransportSettings): StoreCoreTransportSettings =
        loopback.copy(
            enabled = rolloutEnabled && canaryPercent > 0 && loopback.enabled,
            killSwitch = killSwitch || loopback.killSwitch,
            capabilityActive = capabilityActive && loopback.capabilityActive,
            identityRef = identityRef.ifBlank { loopback.identityRef },
            tokenRef = tokenRef.ifBlank { loopback.tokenRef },
        )

    fun assertLocalDispatchAllowed() {
        StoreCoreCanonicalContract.assertCompatible(
            StoreCoreCanonicalContract.CANONICAL_PATH,
            StoreCoreCanonicalContract.VERSION,
            StoreCoreCanonicalContract.SHA256,
        )
        if (killSwitch || !capabilityActive) {
            throw BlockedStoreCoreIntegrationException("STORECORE_TRANSPORT_DISABLED")
        }
        if (identityRef.isBlank() || tokenRef.isBlank()) {
            throw BlockedStoreCoreIntegrationException("STORECORE_IDENTITY_MISSING")
        }
        if (rolloutEnabled || canaryPercent > 0) {
            throw BlockedStoreCoreIntegrationException("STORECORE_LIVE_ROLLOUT_FORBIDDEN")
        }
    }

    fun assertNoSecretInAudit(secret: String) {
        check(audit.none { it.reason.contains(secret) || it.identityRef.contains(secret) || it.actorRef.contains(secret) })
    }
}
