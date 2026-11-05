package com.blackstore.presentation.controller

import com.blackstore.application.identity.ResolveStaffSession
import com.blackstore.application.pos.PosContextApplicationService
import com.blackstore.domain.cash.StaffRole
import com.blackstore.domain.identity.*
import com.blackstore.domain.port.out.pos.PosContextQuery
import com.blackstore.domain.pos.*
import com.blackstore.infrastructure.identity.*
import com.blackstore.infrastructure.exception.GlobalExceptionHandler
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant

class PosContextControllerTest {
    private val token="s".repeat(43)
    private val mapper=jacksonObjectMapper()
    @Test fun sidAndCurrentWorkspacePermissionAreRequiredBeforeQuery() {
        for(role in StaffRole.entries) {
            var queries=0
            val result=PosContextTranslator.fromWire("11111111-1111-4111-8111-111111111111","device",1)
            val response=mvc(role,PosContextQuery { queries++; result }).perform(get("/api/v2/pos/context").cookie(Cookie("__Host-blackstore-session",token))).andReturn().response
            val allowed=StaffAuthorizationPolicy().permits(role,StaffPermission.WorkspaceRead)
            assertEquals(if(allowed) 200 else 403,response.status)
            assertEquals(if(allowed) 1 else 0,queries)
            assertEquals("no-store",response.getHeader("Cache-Control"))
            if(allowed) assertEquals("Available",mapper.readTree(response.contentAsString)["data"]["state"].asText())
        }
        var calls=0
        val response=mvc(StaffRole.CASHIER,PosContextQuery { calls++; PosContextResult.Unavailable }).perform(get("/api/v2/pos/context")).andReturn().response
        assertEquals(401,response.status);assertEquals(0,calls)
    }
    @Test fun unavailableAndUnknownAreNeutralAndNeverExposeConfiguration() {
        for(result in listOf(PosContextResult.Unavailable,PosContextResult.Unknown)) {
            val response=mvc(StaffRole.CASHIER,PosContextQuery { result }).perform(get("/api/v2/pos/context").cookie(Cookie("__Host-blackstore-session",token))).andReturn().response
            assertEquals(503,response.status);assertEquals("no-store",response.getHeader("Cache-Control"))
            assertTrue(mapper.readTree(response.contentAsString)["data"]["context"].isNull)
        }
        assertEquals(StaffPermission.WorkspaceRead,StaffHttpPermission.permission("GET","/api/v2/pos/context"))
        assertEquals(StaffPermission.Unknown,StaffHttpPermission.permission("POST","/api/v2/pos/context"))
    }
    private fun mvc(role: StaffRole,query: PosContextQuery): org.springframework.test.web.servlet.MockMvc {
        val resolve=Mockito.mock(ResolveStaffSession::class.java)
        val staff=AuthenticatedStaff(StaffUserId(1),"Operator",role)
        val session=ResolvedStaffSession(staff,StaffSession("digest",staff.id,"csrf",Instant.EPOCH,Instant.EPOCH,Instant.MAX,null))
        Mockito.`when`(resolve.execute(token)).thenReturn(session)
        Mockito.`when`(resolve.execute(null)).thenThrow(StaffSecurityException(StaffSecurityFailure.SESSION_INVALID))
        val provider=StaticListableBeanFactory(mapOf("query" to query)).getBeanProvider(PosContextQuery::class.java)
        val builder=MockMvcBuilders.standaloneSetup(PosContextController(PosContextApplicationService(provider)))
            .setControllerAdvice(GlobalExceptionHandler())
        builder.addFilters<org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder>(StaffSessionFilter(resolve,StaffCookieSettings(false,""),mapper))
        return builder.build()
    }
}
