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
