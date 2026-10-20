package com.blackstore.presentation.controller

import com.blackstore.domain.identity.StaffPermission
import com.blackstore.infrastructure.identity.StaffHttpPermission
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
class CashAndSaleControllerTest {
    @Autowired private lateinit var mvc: MockMvc
    @Test fun everyPrivateEndpointRejectsMissingSessionAndForgedAuthorityHeaders() {
        for(path in listOf("/api/v1/catalog","/api/v1/workspace","/api/v1/cash-sessions","/api/v1/sales/operation","/api/v1/reports/shift","/api/v1/reports/daily","/actuator/health","/api/v1/future","/api/v2/accounting/commands/00000000-0000-0000-0000-000000000001")) {
            mvc.get(path) { header("X-Actor-Id","1"); header("X-Role","OWNER") }.andExpect { status { isUnauthorized() }; jsonPath("$.errorCode") { value("SESSION_INVALID") }; header { string("Cache-Control","no-store") } }
        }
        for(path in listOf("/api/v1/cash-sessions","/api/v1/cash-sessions/1/close","/api/v1/sales/reservations","/api/v1/sales/operation/commit","/api/v1/sales/operation/release","/api/v1/payments","/api/v1/payments/1/reversals","/api/v1/expenses","/api/v1/auth/logout","/api/v2/cash-sessions","/api/v2/payments","/api/v2/payments/1/reversals","/api/v2/expenses")) {
            mvc.post(path) { header("X-Actor-Id","1"); header("X-Role","OWNER") }.andExpect { status { isUnauthorized() } }
        }
    }
    @Test fun publicRouteAllowlistIsExactAndUnknownDefaultsPrivate() {
        mvc.get("/api/v1/health").andExpect { status { isOk() }; jsonPath("$.data.service") { value("blackstore-backend") } }
        mvc.get("/api/v1/auth/csrf").andExpect { status { isServiceUnavailable() }; jsonPath("$.errorCode") { value("IDENTITY_UNAVAILABLE") } }
        assertEquals(StaffPermission.Unknown,StaffHttpPermission.permission("GET","/api/v1/auth/login"))
        assertEquals(StaffPermission.Unknown,StaffHttpPermission.permission("POST","/api/v1/health"))
        assertFalse(StaffHttpPermission.public(StaffPermission.Unknown))
    }
}
