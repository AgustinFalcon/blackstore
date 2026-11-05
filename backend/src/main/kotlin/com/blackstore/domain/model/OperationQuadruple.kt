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
        require(clientInstanceId.isNotEmpty()) { "clientInstanceId is required" }
        require(deviceId.isNotEmpty()) { "deviceId is required" }
        require(saleId.isNotEmpty()) { "saleId is required" }
        require(operationId.isNotEmpty()) { "operationId is required" }
    }
}
