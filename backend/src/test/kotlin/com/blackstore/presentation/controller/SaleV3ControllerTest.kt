package com.blackstore.presentation.controller

import com.blackstore.application.sales.SaleCommandApplicationService
import com.blackstore.domain.cash.StaffRole
import com.blackstore.domain.identity.*
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.port.out.sales.SaleMutationCommands
import com.blackstore.domain.sales.*
import com.blackstore.infrastructure.exception.GlobalExceptionHandler
import com.blackstore.infrastructure.identity.StaffSessionFilter
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.UUID

class SaleV3ControllerTest {
    private val q=OperationQuadruple(UUID.randomUUID().toString(),"device","sale",UUID.randomUUID().toString())
    private val staff=AuthenticatedStaff(StaffUserId(1),"cashier",StaffRole.CASHIER)
    private val digest="d".repeat(64)
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
    private inner class RecordingPort: SaleMutationCommands {
        var calls=0;var digest:String?=null;var family:SaleAdmissionFamily?=null
        private fun receipt(id: UUID)=SaleCommandAdmissionReceipt(id,SaleCommandKind.Reserve,"a".repeat(64),1,1,q,1,1,Instant.EPOCH)
        override fun execute(staff: AuthenticatedStaff,command: SaleCommand)=error("v3 must carry session context")
        override fun execute(context: AuthenticatedSessionContext,command: SaleCommand): SaleCommandResult { calls++;digest=context.sessionDigest;return SaleCommandResult.Accepted(receipt(command.commandId)) }
        override fun findReceipt(staff: AuthenticatedStaff,commandId: UUID)=SaleCommandResult.NotFound
        override fun findReceipt(staff: AuthenticatedStaff,commandId: UUID,family: SaleAdmissionFamily): SaleCommandResult { this.family=family;return SaleCommandResult.Accepted(receipt(commandId),true) }
    }
}
