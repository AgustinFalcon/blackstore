package com.blackstore.domain.companion

import java.util.UUID

data class CompanionInstallation(
    val storecoreInstallationRef: UUID,
    val entitlementState: EntitlementState,
    val environment: CompanionEnvironment,
    val serviceIdentity: ServiceIdentity,
)
