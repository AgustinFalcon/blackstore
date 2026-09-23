package com.blackstore.infrastructure.companion

import com.blackstore.application.companion.CompanionProperties
import com.blackstore.domain.companion.CompanionInstallation
import com.blackstore.domain.companion.ServiceIdentity
import com.blackstore.domain.port.out.companion.LoadCompanionInstallationPort
import org.springframework.stereotype.Component

/**
 * Process-local companion record until the PostgreSQL adapter is selected.
 * Holds a service identity reference only — never a StoreCore credential.
 */
@Component
class ConfiguredCompanionInstallationAdapter(
    private val companionProperties: CompanionProperties,
) : LoadCompanionInstallationPort {

    override fun loadSingleton(): CompanionInstallation {
        val stored = companionProperties.stored
        return CompanionInstallation(
            storecoreInstallationRef = stored.installationRef,
            entitlementState = stored.entitlementState,
            environment = stored.environment,
            serviceIdentity = ServiceIdentity(stored.serviceClientRef),
        )
    }

    override fun count(): Int = 1
}
