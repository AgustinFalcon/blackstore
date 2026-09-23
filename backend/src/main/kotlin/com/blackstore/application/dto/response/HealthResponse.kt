package com.blackstore.application.dto.response

data class HealthResponse(
    val service: String,
    val boundedContext: String,
    val storeCoreIntegrationEnabled: Boolean,
)
