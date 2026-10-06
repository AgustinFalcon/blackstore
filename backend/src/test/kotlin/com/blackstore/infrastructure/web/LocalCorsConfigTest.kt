package com.blackstore.infrastructure.web

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(properties = [
    "blackstore.identity.allowed-origin=https://blackstore.example",
    "blackstore.identity.loopback-http=false",
])
@AutoConfigureMockMvc
class LocalCorsConfigTest(
    @Autowired private val mvc: MockMvc,
) {
    @Test
    fun configuredTlsFrontendOriginCanReachPrivateHttpBackendWithCredentials() {
        mvc.perform(
            options("/api/v1/auth/login")
                .header("Origin", "https://blackstore.example")
                .header("Access-Control-Request-Method", "POST"),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Access-Control-Allow-Origin", "https://blackstore.example"))
            .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
    }
}
