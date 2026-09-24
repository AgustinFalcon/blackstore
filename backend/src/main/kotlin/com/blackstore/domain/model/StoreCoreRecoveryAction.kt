package com.blackstore.domain.model

/**
 * Contract-neutral next step after a remote StoreCore outcome.
 * No HTTP status, header, or framework type.
 */
enum class StoreCoreRecoveryAction {
    GET_SAME_QUADRUPLE,
    RETAIN_PENDING,
    RETAIN_DURABLE_EVIDENCE,
    NEW_OPERATION_SAME_SALE,
    NEVER_REPOST,
    RECORD_RECONCILIATION_REQUIRED,
}
