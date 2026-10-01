package com.blackstore.infrastructure.concurrency

import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DispatcherProviderTest {
    @Test
    fun serverProviderIoIsDispatchersIo() {
        assertSame(Dispatchers.IO, ServerDispatcherProvider().io)
    }

    @Test
    fun testProviderDefaultsToUnconfined() {
        assertSame(Dispatchers.Unconfined, TestDispatcherProvider().io)
    }

    @Test
    fun springBeanIsServerProvider() {
        val bean = DispatcherConfiguration().dispatcherProvider()
        assertTrue(bean is ServerDispatcherProvider)
        assertEquals(Dispatchers.IO, bean.io)
    }
}
