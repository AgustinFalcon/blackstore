package com.blackstore.infrastructure.identity

import com.blackstore.application.identity.ResolveStaffSession
import com.blackstore.domain.identity.*
import com.blackstore.domain.cash.StaffRole
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import jakarta.servlet.http.Cookie
import jakarta.servlet.FilterChain
import java.time.Instant

class StaffSessionFilterTest {
    @Test fun accountingV2RoutesHaveExactPermissionsWithoutOperationalBrowserEndpoints() {
        val routes = mapOf(
            "POST /api/v2/cash-sessions" to StaffPermission.CashSessionOpen,
            "POST /api/v2/expenses" to StaffPermission.ExpenseRecord,
            "POST /api/v2/payments" to StaffPermission.PaymentCapture,
            "POST /api/v2/payments/3/reversals" to StaffPermission.PaymentReverse,
            "GET /api/v2/accounting/commands/command" to StaffPermission.AccountingCommandRead)
        routes.forEach { (route, permission) ->
            val (method, path) = route.split(" ")
            assertEquals(permission, StaffHttpPermission.permission(method, path))
            assertFalse(StaffHttpPermission.public(permission))
        }
        for (path in listOf("/api/v2/fees", "/api/v2/adjustments", "/api/v2/cash-sessions/1/close", "/api/v2/payments/1/reversals/extra"))
            assertEquals(StaffPermission.Unknown, StaffHttpPermission.permission("POST", path))
        assertEquals(StaffPermission.Unknown, StaffHttpPermission.permission("POST", "/api/v2/accounting/commands/command"))
        val policy = StaffAuthorizationPolicy()
        assertFalse(policy.permits(StaffRole.AUDITOR, StaffPermission.AccountingCommandRead))
        assertFalse(policy.permits(StaffRole.UNKNOWN, StaffPermission.AccountingCommandRead))
        assertTrue(policy.permits(StaffRole.CASHIER, StaffPermission.AccountingCommandRead))
    }

    @Test fun accountingV2MutationsRequireCsrfBeforeAnyBusinessEffect() {
        val resolver = Mockito.mock(ResolveStaffSession::class.java)
        val token = "s".repeat(43); val now = Instant.now()
        val session = ResolvedStaffSession(AuthenticatedStaff(StaffUserId(1), "Operator", StaffRole.CASHIER),
            StaffSession("digest", StaffUserId(1), "csrf", now, now, now.plusSeconds(43200), null))
        Mockito.`when`(resolver.execute(token)).thenReturn(session)
        for (path in listOf("/api/v2/cash-sessions", "/api/v2/expenses", "/api/v2/payments", "/api/v2/payments/3/reversals")) {
            val request = MockHttpServletRequest("POST", path)
            request.setCookies(Cookie("__Host-blackstore-session", token))
            val response = MockHttpServletResponse(); var effects = 0
            StaffSessionFilter(resolver, StaffCookieSettings(false, ""), jacksonObjectMapper())
                .doFilter(request, response, FilterChain { _, _ -> effects++ })
            assertEquals(403, response.status); assertEquals(0, effects)
            assertEquals(StaffSecurityFailure.CSRF_INVALID.name, jacksonObjectMapper().readTree(response.contentAsString)["errorCode"].asText())
            assertEquals("no-store", response.getHeader("Cache-Control"))
        }
    }

    @Test fun identityDatabaseFailureIsCanonicalAndHasNoBusinessEffect() {
        val resolver=Mockito.mock(ResolveStaffSession::class.java)
        val token="s".repeat(43)
        Mockito.`when`(resolver.execute(token)).thenAnswer { throw java.sql.SQLException("unavailable") }
        val request=MockHttpServletRequest("GET","/api/v1/auth/session")
        request.setCookies(Cookie("__Host-blackstore-session",token))
        val response=MockHttpServletResponse(); var effects=0
        StaffSessionFilter(resolver,StaffCookieSettings(false,""),jacksonObjectMapper()).doFilter(request,response,FilterChain { _,_ -> effects++ })
        assertEquals(503,response.status); assertEquals(0,effects)
        assertEquals(StaffSecurityFailure.IDENTITY_UNAVAILABLE.name,jacksonObjectMapper().readTree(response.contentAsString)["errorCode"].asText())
    }
    @Test fun logoutWhichWinsBeforeFinalMutationAdmissionPreventsBusinessEffect() {
        val resolver=Mockito.mock(ResolveStaffSession::class.java)
        val token="s".repeat(43); val csrf="c".repeat(43); val now=Instant.now()
        val session=ResolvedStaffSession(AuthenticatedStaff(StaffUserId(1),"Operator",StaffRole.CASHIER),StaffSession("digest",StaffUserId(1),csrf,now,now,now.plusSeconds(43200),null))
        Mockito.`when`(resolver.execute(token)).thenReturn(session).thenThrow(StaffSecurityException(StaffSecurityFailure.SESSION_INVALID))
        val request=MockHttpServletRequest("POST","/api/v1/payments")
        request.setCookies(Cookie("__Host-blackstore-session",token)); request.addHeader("X-CSRF-Token",csrf)
        val response=MockHttpServletResponse(); var effects=0
        StaffSessionFilter(resolver,StaffCookieSettings(false,""),jacksonObjectMapper()).doFilter(request,response,FilterChain { _,_ -> effects++ })
        assertEquals(401,response.status); assertEquals(0,effects)
    }
    @Test fun unsafeLoopbackConfigurationIsRejectedAndProductionHasSeparateCookie() {
        val config=StaffSecurityConfiguration()
        assertThrows<IllegalArgumentException> { config.staffCookieSettings(true,"0.0.0.0","http://localhost:4201") }
        assertThrows<IllegalArgumentException> { config.staffCookieSettings(false,"","http://example.com") }
        assertThrows<IllegalArgumentException> { config.staffCookieSettings(false,"0.0.0.0","https://example.com",true,false) }
        val production=config.staffCookieSettings(false,"","https://example.com")
        assertTrue(production.secure); assertEquals("__Host-blackstore-session",production.name)
        val local=config.staffCookieSettings(true,"127.0.0.1","http://localhost:4201")
        assertFalse(local.secure); assertNotEquals(production.name,local.name)
        val generator=SecureSessionTokenGenerator(); val first=generator.generate(); val second=generator.generate()
        assertEquals(43,first.length); assertNotEquals(first,second); assertEquals(64,generator.digest(first).length)
    }
}
