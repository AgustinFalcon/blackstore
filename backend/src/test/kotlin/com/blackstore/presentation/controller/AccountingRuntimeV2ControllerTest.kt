package com.blackstore.presentation.controller

import com.blackstore.domain.accounting.*
import com.blackstore.domain.cash.StaffRole
import com.blackstore.domain.identity.*
import com.blackstore.domain.port.out.accounting.*
import com.blackstore.infrastructure.exception.GlobalExceptionHandler
import com.blackstore.infrastructure.identity.StaffSessionFilter
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant

class AccountingRuntimeV2ControllerTest {
    private val staff=AuthenticatedStaff(StaffUserId(4),"auditor",StaffRole.AUDITOR)
    private val session=ResolvedStaffSession(staff,StaffSession("digest",staff.id,"csrf",Instant.EPOCH,Instant.EPOCH,Instant.MAX,null))
    @Test fun lifecycleReadPublishesCoherentMetadataForAllKnownStatesAndNoStore() {
        var calls=0
        for(state in listOf(AccountingRuntimeState.PreActivation,AccountingRuntimeState.Active,AccountingRuntimeState.Paused)) {
            val port=object: AccountingLifecycleQuery { override fun observe(staff: AuthenticatedStaff): AccountingLifecycleResult {
                assertEquals(this@AccountingRuntimeV2ControllerTest.staff,staff);calls++
                return AccountingLifecycleResult.Observed(AccountingLifecycleObservation(state,if(state==AccountingRuntimeState.PreActivation) null else Instant.EPOCH,AccountingContractVersion.V2,Instant.EPOCH))
            } }
            val mvc=MockMvcBuilders.standaloneSetup(AccountingRuntimeV2Controller(StaticListableBeanFactory(mapOf("port" to port)).getBeanProvider(AccountingLifecycleQuery::class.java))).setControllerAdvice(GlobalExceptionHandler()).build()
            val response=mvc.perform(get("/api/v2/accounting/runtime").requestAttr(StaffSessionFilter.SESSION_ATTRIBUTE,session)).andReturn().response
            assertEquals(200,response.status);assertEquals("no-store",response.getHeader("Cache-Control"))
            val json=jacksonObjectMapper().readTree(response.contentAsString)
            assertEquals(state.wire,json["data"]["state"].asText());assertEquals("V2",json["data"]["contractVersion"].asText())
        }
        assertEquals(3,calls)
    }
    @Test fun absentAdapterIs503AndMissingSidIs401() {
        val mvc=MockMvcBuilders.standaloneSetup(AccountingRuntimeV2Controller(StaticListableBeanFactory().getBeanProvider(AccountingLifecycleQuery::class.java))).setControllerAdvice(GlobalExceptionHandler()).build()
        assertEquals(503,mvc.perform(get("/api/v2/accounting/runtime").requestAttr(StaffSessionFilter.SESSION_ATTRIBUTE,session)).andReturn().response.status)
        assertEquals(401,mvc.perform(get("/api/v2/accounting/runtime")).andReturn().response.status)
    }
}
