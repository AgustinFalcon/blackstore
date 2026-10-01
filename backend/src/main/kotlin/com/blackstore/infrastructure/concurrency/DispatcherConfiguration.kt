package com.blackstore.infrastructure.concurrency

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Composition root for [DispatcherProvider]. Always on: fixture mode still needs the bean if tests construct HTTP. */
@Configuration
class DispatcherConfiguration {
    @Bean
    fun dispatcherProvider(): DispatcherProvider = ServerDispatcherProvider()
}
