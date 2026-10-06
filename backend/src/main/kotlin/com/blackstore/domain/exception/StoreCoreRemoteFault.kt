package com.blackstore.domain.exception

enum class StoreCoreFailureCode {
    CONFLICT, IDEMPOTENCY_PAYLOAD_MISMATCH, EXPIRED, OPERATION_RETIRED, NOT_FOUND,
    INSUFFICIENT_STOCK, CATALOG_VERSION_STALE, VALIDATION, UNKNOWN;
    companion object {
        fun fromWire(raw: String?): StoreCoreFailureCode = entries.firstOrNull { it.name == raw } ?: UNKNOWN
    }
}

class StoreCoreRemoteFault(
    val errorCode: String,
    val retryable: Boolean = false,
) : RuntimeException(errorCode) {
    val failureCode: StoreCoreFailureCode = StoreCoreFailureCode.fromWire(errorCode)
}
