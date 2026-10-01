package com.blackstore.infrastructure.concurrency

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Injected coroutine threads, same idea as GoodLife Android [DispatcherProvider].
 *
 * A servlet backend already has a request pool and Spring `@Scheduled` / `@Transactional`
 * threads. Only blocking HTTP at the StoreCore loopback edge hops to [io].
 * There is no Android `Main`. CPU `Default` is omitted until a real CPU-bound caller exists.
 */
interface DispatcherProvider {
    val io: CoroutineDispatcher
}
