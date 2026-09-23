package com.blackstore.application.companion

import com.blackstore.domain.companion.CompanionEntitlementPolicy
import com.blackstore.domain.companion.ExpectedCompanionBinding
import com.blackstore.domain.port.out.companion.LoadCompanionInstallationPort
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

@Component
class CompanionStartupGuard(
    private val loadCompanionInstallationPort: LoadCompanionInstallationPort,
    private val companionEntitlementPolicy: CompanionEntitlementPolicy,
    private val companionProperties: CompanionProperties,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        companionEntitlementPolicy.assertOperational(
            stored = loadCompanionInstallationPort.loadSingleton(),
            companionCount = loadCompanionInstallationPort.count(),
            expected =
                ExpectedCompanionBinding(
                    storecoreInstallationRef = companionProperties.expectedInstallationRef,
                    serviceClientRef = companionProperties.expectedServiceClientRef,
                ),
        )
    }
}
