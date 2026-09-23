package com.blackstore.infrastructure.companion

import com.blackstore.domain.companion.CompanionEntitlementPolicy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class CompanionConfiguration {

    @Bean
    fun companionEntitlementPolicy(): CompanionEntitlementPolicy = CompanionEntitlementPolicy()
}
