package com.blackstore.domain.model

/**
 * Canonical wire identity for a StoreCore integration operation.
 * Persisted before any outbound call (TASK-006+).
 */
data class OperationQuadruple(
    val clientInstanceId: String,
    val deviceId: String,
    val saleId: String,
    val operationId: String,
) {
    init {
        require(clientInstanceId.isNotBlank()) { "clientInstanceId is required" }
        require(deviceId.isNotBlank()) { "deviceId is required" }
        require(saleId.isNotBlank()) { "saleId is required" }
        require(operationId.isNotBlank()) { "operationId is required" }
    }
}
