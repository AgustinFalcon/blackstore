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

class SaleV2ControllerTest {
    private val q=OperationQuadruple(UUID.randomUUID().toString(),"device","sale",UUID.randomUUID().toString())
    private val staff=AuthenticatedStaff(StaffUserId(1),"cashier",StaffRole.CASHIER)
    private val session=ResolvedStaffSession(staff,StaffSession("digest",staff.id,"csrf",Instant.EPOCH,Instant.EPOCH,Instant.MAX,null))
    private val port=RecordingPort()
    private val mvc=MockMvcBuilders.standaloneSetup(SaleV2Controller(SaleCommandApplicationService(StaticListableBeanFactory(mapOf("port" to port)).getBeanProvider(SaleMutationCommands::class.java))))
        .setControllerAdvice(GlobalExceptionHandler()).build()
    private fun terminal(id: UUID)="""{"commandId":"$id","clientInstanceId":"${q.clientInstanceId}","deviceId":"${q.deviceId}","saleId":"${q.saleId}","operationId":"${q.operationId}"}"""
    @Test fun acceptedIs202ReplayIs200AndQueriesNeverExecute() {
        val id=UUID.randomUUID()
        assertEquals(202,mvc.perform(post("/api/v2/sales/${q.operationId}/commit").contentType(MediaType.APPLICATION_JSON).content(terminal(id)).requestAttr(StaffSessionFilter.SESSION_ATTRIBUTE,session)).andReturn().response.status)
        port.replay=true
        assertEquals(200,mvc.perform(post("/api/v2/sales/${q.operationId}/release").contentType(MediaType.APPLICATION_JSON).content(terminal(id)).requestAttr(StaffSessionFilter.SESSION_ATTRIBUTE,session)).andReturn().response.status)
        val calls=port.calls
        val response=mvc.perform(get("/api/v2/sales/commands/$id").requestAttr(StaffSessionFilter.SESSION_ATTRIBUTE,session)).andReturn().response
        assertEquals(200,response.status);assertEquals("no-store",response.getHeader("Cache-Control"));assertEquals(calls,port.calls);assertEquals(1,port.queries)
    }
    @Test fun missingIdBadIdPathMismatchAndAuthorityFieldsNeverReachPort() {
        val id=UUID.randomUUID()
        for(body in listOf(terminal(id).replace(id.toString(),"bad"),terminal(id).replace("\"commandId\":\"$id\",",""),terminal(id).dropLast(1)+",\"actorId\":1}")) {
            assertEquals(400,mvc.perform(post("/api/v2/sales/${q.operationId}/commit").contentType(MediaType.APPLICATION_JSON).content(body).requestAttr(StaffSessionFilter.SESSION_ATTRIBUTE,session)).andReturn().response.status)
        }
        assertEquals(400,mvc.perform(post("/api/v2/sales/wrong/commit").contentType(MediaType.APPLICATION_JSON).content(terminal(id)).requestAttr(StaffSessionFilter.SESSION_ATTRIBUTE,session)).andReturn().response.status)
        assertEquals(0,port.calls)
    }
    private inner class RecordingPort: SaleMutationCommands {
        var calls=0;var queries=0;var replay=false
        private fun receipt(id: UUID,kind: SaleCommandKind)=SaleCommandAdmissionReceipt(id,kind,"a".repeat(64),1,1,q,1,1,Instant.EPOCH)
        override fun execute(staff: AuthenticatedStaff,command: SaleCommand): SaleCommandResult { calls++;assertEquals(this@SaleV2ControllerTest.staff,staff);return SaleCommandResult.Accepted(receipt(command.commandId,command.kind),replay) }
        override fun findReceipt(staff: AuthenticatedStaff,commandId: UUID): SaleCommandResult { queries++;return SaleCommandResult.Accepted(receipt(commandId,SaleCommandKind.Commit),true) }
    }
}
