package com.blackstore.application.companion

import com.blackstore.domain.companion.CompanionEnvironment
import com.blackstore.domain.companion.EntitlementState
import org.springframework.boot.context.properties.ConfigurationProperties
import java.util.UUID

@ConfigurationProperties(prefix = "blackstore.companion")
data class CompanionProperties(
    val expectedInstallationRef: UUID,
    val expectedServiceClientRef: String,
    val stored: StoredCompanionProperties,
)

data class StoredCompanionProperties(
    val installationRef: UUID,
    val serviceClientRef: String,
    val entitlementState: EntitlementState,
    val environment: CompanionEnvironment,
)
