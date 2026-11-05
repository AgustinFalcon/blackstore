package com.blackstore.presentation.controller

import com.blackstore.application.sales.SaleCommandApplicationService
import com.blackstore.application.identity.ResolveStaffSession
import com.blackstore.domain.cash.StaffRole
import com.blackstore.domain.identity.*
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.port.out.sales.SaleMutationCommands
import com.blackstore.domain.sales.*
import com.blackstore.infrastructure.exception.GlobalExceptionHandler
import com.blackstore.infrastructure.identity.StaffSessionFilter
import com.blackstore.infrastructure.identity.StaffCookieSettings
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import java.time.Instant
import java.util.UUID

class SaleV3ControllerTest {
    private val q=OperationQuadruple(UUID.randomUUID().toString(),"device","sale",UUID.randomUUID().toString())
    private val staff=AuthenticatedStaff(StaffUserId(1),"cashier",StaffRole.CASHIER)
    private val digest="d".repeat(64)
    private val token="s".repeat(43)
    private val settings=StaffCookieSettings(false,"")
    private val mapper=jacksonObjectMapper()
    private val session=ResolvedStaffSession(staff,StaffSession(digest,staff.id,"csrf",Instant.EPOCH,Instant.EPOCH,Instant.MAX,null))
    private val port=RecordingPort()
    private val mvc=MockMvcBuilders.standaloneSetup(SaleV3Controller(SaleCommandApplicationService(StaticListableBeanFactory(mapOf("port" to port)).getBeanProvider(SaleMutationCommands::class.java))))
        .setControllerAdvice(GlobalExceptionHandler()).build()
    private fun body(extraLine: String="")="""{"commandId":"${UUID.randomUUID()}","clientInstanceId":"${q.clientInstanceId}","deviceId":"${q.deviceId}","saleId":"${q.saleId}","operationId":"${q.operationId}","cashSessionId":1,"lines":[{"variantId":"v1","quantity":2,"expectedPriceVersion":"p1","sku":"s1","productName":"Product","originalUnitPrice":10.00,"discountAmount":0$extraLine}]}"""

    @Test fun multilineUsesOpaqueSessionContextAndReturnsExplicitFingerprintVersion() {
        val response=mvc.perform(post("/api/v3/sales/reservations").contentType(MediaType.APPLICATION_JSON).content(body()).requestAttr(StaffSessionFilter.SESSION_ATTRIBUTE,session)).andReturn().response
        assertEquals(202,response.status);assertTrue(response.contentAsString.contains("sale-admission-v2"));assertEquals(digest,port.digest)
        val id=UUID.randomUUID()
        assertEquals(200,mvc.perform(get("/api/v3/sales/commands/$id").requestAttr(StaffSessionFilter.SESSION_ATTRIBUTE,session)).andReturn().response.status)
        assertEquals(SaleAdmissionFamily.V3,port.family)
    }
    @Test fun fractionsNullUnknownAndDuplicateVariantsFailBeforePort() {
        val valid=body()
        val invalid=listOf(valid.replace("\"quantity\":2","\"quantity\":2.5"),valid.replace("\"quantity\":2","\"quantity\":null"),body(",\"actorId\":1"),
            valid.replace("]}",",{\"variantId\":\"v1\",\"quantity\":1,\"expectedPriceVersion\":\"p1\",\"sku\":\"s1\",\"productName\":\"Product\",\"originalUnitPrice\":10.00,\"discountAmount\":0}]}"))
        invalid.forEach { assertEquals(400,mvc.perform(post("/api/v3/sales/reservations").contentType(MediaType.APPLICATION_JSON).content(it).requestAttr(StaffSessionFilter.SESSION_ATTRIBUTE,session)).andReturn().response.status) }
        assertEquals(0,port.calls)
    }
    @Test fun localLineFailureIsClosedAndCarriesOnlyTheAffectedVariant() {
        port.reject=true
        val response=mvc.perform(post("/api/v3/sales/reservations").contentType(MediaType.APPLICATION_JSON).content(body()).requestAttr(StaffSessionFilter.SESSION_ATTRIBUTE,session)).andReturn().response
        assertEquals(400,response.status);assertTrue(response.contentAsString.contains(SaleLineFailureCode.PriceVersionMismatch.name));assertTrue(response.contentAsString.contains("v1"))
    }
    @Test fun lineFailureCodeTranslatorIsClosedAndUnknownSafe() {
        SaleLineFailureCode.entries.filter { it!=SaleLineFailureCode.Unknown }.forEach { assertEquals(it,SaleLineFailureCode.fromWire(it.name)) }
        assertEquals(SaleLineFailureCode.Unknown,SaleLineFailureCode.fromWire("future-code"));assertEquals(SaleLineFailureCode.Unknown,SaleLineFailureCode.fromWire(null))
    }
    @Test fun realFilterAndControllerComposeForAuthenticatedV3PostAndGet() {
        val securedPort=RecordingPort();val secured=securedMvc(StaffRole.CASHIER,securedPort)
        val postResponse=secured.perform(post("/api/v3/sales/reservations").cookie(Cookie(settings.name,token))
            .header("X-CSRF-Token","csrf").contentType(MediaType.APPLICATION_JSON).content(body())).andReturn().response
        assertEquals(202,postResponse.status);assertEquals(digest,securedPort.context?.sessionDigest);assertEquals(StaffRole.CASHIER,securedPort.context?.staff?.role)
        assertFalse(postResponse.contentAsString.contains(digest));assertFalse(postResponse.contentAsString.contains(token))
        val id=UUID.randomUUID();assertEquals(200,secured.perform(get("/api/v3/sales/commands/$id").cookie(Cookie(settings.name,token))).andReturn().response.status)
        assertEquals(SaleAdmissionFamily.V3,securedPort.family)
    }
    @Test fun realFilterDeniesMissingSessionInvalidCsrfAndUnauthorizedRolesBeforePort() {
        val noSessionPort=RecordingPort();val noSession=securedMvc(StaffRole.CASHIER,noSessionPort)
        val missing=noSession.perform(post("/api/v3/sales/reservations").header("X-CSRF-Token","csrf").contentType(MediaType.APPLICATION_JSON).content(body())).andReturn().response
        assertEquals(401,missing.status);assertTrue(missing.contentAsString.contains(StaffSecurityFailure.SESSION_INVALID.name));assertEquals(0,noSessionPort.calls)
        val csrfPort=RecordingPort();val csrf=securedMvc(StaffRole.CASHIER,csrfPort)
        val invalid=csrf.perform(post("/api/v3/sales/reservations").cookie(Cookie(settings.name,token)).header("X-CSRF-Token","wrong").contentType(MediaType.APPLICATION_JSON).content(body())).andReturn().response
        assertEquals(403,invalid.status);assertTrue(invalid.contentAsString.contains(StaffSecurityFailure.CSRF_INVALID.name));assertEquals(0,csrfPort.calls)
        listOf(StaffRole.AUDITOR,StaffRole.UNKNOWN).forEach { role ->
            val deniedPort=RecordingPort();val denied=securedMvc(role,deniedPort).perform(post("/api/v3/sales/reservations").cookie(Cookie(settings.name,token))
                .header("X-CSRF-Token","csrf").contentType(MediaType.APPLICATION_JSON).content(body())).andReturn().response
            assertEquals(403,denied.status);assertTrue(denied.contentAsString.contains(StaffSecurityFailure.FORBIDDEN.name));assertEquals(0,deniedPort.calls)
        }
    }
    private fun securedMvc(role: StaffRole,securedPort: RecordingPort): org.springframework.test.web.servlet.MockMvc {
        val resolve=Mockito.mock(ResolveStaffSession::class.java);val resolved=session.copy(staff=staff.copy(role=role))
        Mockito.`when`(resolve.execute(token)).thenReturn(resolved);Mockito.`when`(resolve.execute(null)).thenThrow(StaffSecurityException(StaffSecurityFailure.SESSION_INVALID))
        val service=SaleCommandApplicationService(StaticListableBeanFactory(mapOf("port" to securedPort)).getBeanProvider(SaleMutationCommands::class.java))
        val builder=MockMvcBuilders.standaloneSetup(SaleV3Controller(service)).setControllerAdvice(GlobalExceptionHandler())
        builder.addFilters<StandaloneMockMvcBuilder>(StaffSessionFilter(resolve,settings,mapper));return builder.build()
    }
    private inner class RecordingPort: SaleMutationCommands {
        var calls=0;var digest:String?=null;var context:AuthenticatedSessionContext?=null;var family:SaleAdmissionFamily?=null;var reject=false
        private fun receipt(id: UUID)=SaleCommandAdmissionReceipt(id,SaleCommandKind.Reserve,"a".repeat(64),1,1,q,1,1,Instant.EPOCH)
        override fun execute(staff: AuthenticatedStaff,command: SaleCommand)=error("v3 must carry session context")
        override fun execute(context: AuthenticatedSessionContext,command: SaleCommand): SaleCommandResult { calls++;digest=context.sessionDigest;this.context=context;require(command is SaleCommand.ReserveMultiline);return if(reject) SaleCommandResult.Rejected(SaleCommandFailure.Validation,listOf(SaleLineFailure("v1",SaleLineFailureCode.PriceVersionMismatch))) else SaleCommandResult.Accepted(receipt(command.commandId)) }
        override fun findReceipt(staff: AuthenticatedStaff,commandId: UUID)=SaleCommandResult.NotFound
        override fun findReceipt(staff: AuthenticatedStaff,commandId: UUID,family: SaleAdmissionFamily): SaleCommandResult { this.family=family;return SaleCommandResult.Accepted(receipt(commandId),true) }
    }
}
