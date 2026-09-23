package com.blackstore.domain.companion

import com.blackstore.domain.exception.EntitlementDeniedException

/**
 * Fail-closed gate: exactly one ENABLED companion whose installation and service identity match the process binding.
 */
class CompanionEntitlementPolicy {

    fun assertOperational(
        stored: CompanionInstallation?,
        companionCount: Int,
        expected: ExpectedCompanionBinding,
    ) {
        if (companionCount != 1 || stored == null) {
            throw EntitlementDeniedException("exactly one companion installation is required")
        }
        if (stored.entitlementState != EntitlementState.ENABLED) {
            throw EntitlementDeniedException("entitlement is ${stored.entitlementState}")
        }
        if (stored.storecoreInstallationRef != expected.storecoreInstallationRef) {
            throw EntitlementDeniedException("installation reference mismatch")
        }
        if (stored.serviceIdentity.clientRef != expected.serviceClientRef) {
            throw EntitlementDeniedException("service identity mismatch")
        }
    }
}
