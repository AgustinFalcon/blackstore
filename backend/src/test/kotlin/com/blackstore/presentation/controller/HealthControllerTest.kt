package com.blackstore.presentation.controller

import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest
@AutoConfigureMockMvc
class HealthControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun healthReturnsBaseResponseEnvelope() {
        mockMvc
            .get("/api/v1/health") {
                header("X-Trace-Id", "trace-task-001")
            }.andExpect {
                status { isOk() }
                jsonPath("$.code") { value(200) }
                jsonPath("$.traceId") { value("trace-task-001") }
                jsonPath("$.data.service") { value("blackstore-backend") }
                jsonPath("$.data.storeCoreIntegrationEnabled") { value(false) }
                jsonPath("$.errorCode") { value(nullValue()) }
                jsonPath("$.retryable") { value(nullValue()) }
                jsonPath("$.message") { value(nullValue()) }
            }
    }
}
