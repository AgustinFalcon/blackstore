package com.blackstore.application.storecore

object StoreCoreRetryPolicy {
    fun shouldRetryGet(attempt: Int, maxRetries: Int, retryable: Boolean): Boolean =
        retryable && attempt < maxRetries

    fun shouldRetryPost(attemptCompleted: Boolean, retryable: Boolean): Boolean =
        attemptCompleted && retryable

    fun waitMillis(retryAfterSeconds: String?): Long {
        val seconds = retryAfterSeconds?.toLongOrNull() ?: 0L
        return (seconds.coerceIn(0, 5) * 1000).coerceAtLeast(0)
    }
}
