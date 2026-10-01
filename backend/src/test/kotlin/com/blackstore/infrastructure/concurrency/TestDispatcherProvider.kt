package com.blackstore.infrastructure.concurrency

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Test [DispatcherProvider]. Uses [Dispatchers.Unconfined] so `runBlocking` at the
 * sync HTTP edge finishes without a virtual-time scheduler (unlike Android `runTest`).
 */
class TestDispatcherProvider(
    override val io: CoroutineDispatcher = Dispatchers.Unconfined,
) : DispatcherProvider
