package com.blackstore.connector

import com.blackstore.application.storecore.StoreCoreControlPlane
import com.blackstore.application.storecore.StoreCoreDispatchGuard
import com.blackstore.application.storecore.StoreCoreTransportSettings
import com.blackstore.domain.exception.BlockedStoreCoreIntegrationException
import com.blackstore.domain.model.StoreCoreCanonicalContract
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class StoreCoreControlPlaneTest {

    @Test
    fun rotationAuditsOpaqueRefsAndNeverStoresBearer() {
        val plane = StoreCoreControlPlane()
        plane.rotate("id-ref-2", "tok-ref-2", "actor-owner", "rotate after StoreCore active-credential evidence")
        assertEquals("ROTATION", plane.audit.single().event)
        assertEquals("id-ref-2", plane.audit.single().identityRef)
        plane.assertNoSecretInAudit("synthetic-not-reusable")
        plane.assertNoSecretInAudit("Bearer")
    }

    @Test
    fun disabledMismatchAndKillSwitchStopCalls() {
        val plane = StoreCoreControlPlane(capabilityActive = true, killSwitch = false, identityRef = "id-ref", tokenRef = "tok-ref")
        plane.assertLocalDispatchAllowed()
        plane.engageKillSwitch("actor-owner", "pilot abort")
        assertThrows<BlockedStoreCoreIntegrationException> { plane.assertLocalDispatchAllowed() }
        plane.disableCapability("actor-owner", "capability off")
        val blocked = plane.transportSettings(loopback())
        assertThrows<BlockedStoreCoreIntegrationException> { StoreCoreDispatchGuard.assertCanDispatch(blocked) }
    }

    @Test
    fun liveCanaryAndRolloutStayForbiddenAndRollbackStopsCalls() {
        val plane = StoreCoreControlPlane(capabilityActive = true, killSwitch = false, identityRef = "id-ref", tokenRef = "tok-ref")
        assertThrows<BlockedStoreCoreIntegrationException> {
            StoreCoreControlPlane(
                capabilityActive = true,
                killSwitch = false,
                identityRef = "id-ref",
                tokenRef = "tok-ref",
                canaryPercent = 1,
                rolloutEnabled = true,
            ).assertLocalDispatchAllowed()
        }
        plane.rotate("id-ref-rotated", "tok-ref-rotated", "actor-owner", "rotate after evidence")
        assertEquals("id-ref-rotated", plane.currentIdentityRef())
        plane.rollback("actor-owner", "rollback keeps evidence and stops calls")
        assertEquals("id-ref", plane.currentIdentityRef())
        assertTrue(plane.audit.any { it.event == "ROLLBACK" })
        assertEquals("id-ref", plane.audit.last().identityRef)
        assertFalse(plane.audit.any { it.reason.contains("sk_") || it.reason.contains("PAN") })
        assertThrows<BlockedStoreCoreIntegrationException> { plane.assertLocalDispatchAllowed() }
        assertThrows<BlockedStoreCoreIntegrationException> {
            StoreCoreDispatchGuard.assertCanDispatch(plane.transportSettings(loopback()))
        }
    }

    private fun loopback() =
        StoreCoreTransportSettings(
            enabled = true,
            killSwitch = false,
            capabilityActive = true,
            baseUrl = "http://127.0.0.1:1",
            tlsRequired = false,
            allowPlainLoopback = true,
            identityRef = "id-ref",
            tokenRef = "tok-ref",
            timeoutMs = 2000,
            maxRetries = 2,
            path = StoreCoreCanonicalContract.CANONICAL_PATH,
            version = StoreCoreCanonicalContract.VERSION,
            digest = StoreCoreCanonicalContract.SHA256,
        )
}
