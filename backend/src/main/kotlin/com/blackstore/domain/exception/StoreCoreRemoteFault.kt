package com.blackstore.domain.exception

class StoreCoreRemoteFault(
    val errorCode: String,
    val retryable: Boolean = false,
) : RuntimeException(errorCode)
