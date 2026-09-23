package com.blackstore.domain.model

enum class StoreCoreOperationState {
    PENDING,
    RESERVED,
    COMMITTED,
    RELEASED,
    EXPIRED,
}
