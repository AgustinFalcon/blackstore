package com.blackstore.application.service

import com.blackstore.application.dto.response.HealthResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class HealthApplicationService(
    @Value("\${blackstore.storecore.integration.enabled:false}")
    private val storeCoreIntegrationEnabled: Boolean,
) {
    fun health(): HealthResponse =
        HealthResponse(
            service = "blackstore-backend",
            boundedContext = "blackstore-pilot",
            storeCoreIntegrationEnabled = storeCoreIntegrationEnabled,
        )
}
