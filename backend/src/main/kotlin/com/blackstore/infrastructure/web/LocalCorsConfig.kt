package com.blackstore.infrastructure.web

import com.blackstore.infrastructure.identity.StaffCookieSettings
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class LocalCorsConfig(
    private val staffCookieSettings: StaffCookieSettings,
) : WebMvcConfigurer {
    override fun addCorsMappings(registry: CorsRegistry) {
        if (staffCookieSettings.allowedOrigin.isBlank()) return
        registry
            .addMapping("/api/**")
            .allowedOrigins(staffCookieSettings.allowedOrigin)
            .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .allowCredentials(true)
    }
}
