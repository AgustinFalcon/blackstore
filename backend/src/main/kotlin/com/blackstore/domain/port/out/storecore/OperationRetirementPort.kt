package com.blackstore.domain.port.out.storecore

interface OperationRetirementPort {
    fun markRetired(operationId: String)

    fun isRetired(operationId: String): Boolean
}
