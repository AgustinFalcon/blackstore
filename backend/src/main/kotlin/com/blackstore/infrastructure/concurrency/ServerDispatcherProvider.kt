package com.blackstore.infrastructure.concurrency

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Production [DispatcherProvider]: blocking IO on [Dispatchers.IO]. */
class ServerDispatcherProvider(
    override val io: CoroutineDispatcher = Dispatchers.IO,
) : DispatcherProvider
