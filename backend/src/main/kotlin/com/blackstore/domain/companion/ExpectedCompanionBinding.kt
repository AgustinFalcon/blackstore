package com.blackstore.domain.companion

import java.util.UUID

data class ExpectedCompanionBinding(
    val storecoreInstallationRef: UUID,
    val serviceClientRef: String,
)
