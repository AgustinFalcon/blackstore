package com.blackstore.application.sales

import com.blackstore.application.dto.accounting.PaymentCaptureV2Request
import com.blackstore.application.dto.sales.*
import com.blackstore.domain.accounting.*
import com.blackstore.domain.cash.StaffRole
import com.blackstore.domain.identity.*
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.port.out.accounting.AccountingLifecycleObservation
import com.blackstore.domain.sales.*
import com.blackstore.infrastructure.identity.StaffHttpPermission
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class SaleCommandContractTest {
    private val q=OperationQuadruple(UUID.randomUUID().toString(),"device","sale",UUID.randomUUID().toString())
    private fun command()=SaleCommand.Reserve(UUID.randomUUID(),q,1,"variant",1,"price",TicketLine("sku","Product",1,BigDecimal.TEN,BigDecimal.ZERO),null)
    @Test fun fingerprintIncludesEverySemanticFieldAndUsesExactNormalizedMoney() {
        val c=command();val f=SaleCommandFingerprint();val hash=f.hash(7,1,c)
        assertEquals(hash,f.hash(7,1,c.copy(line=c.line.copy(originalUnitPrice=BigDecimal("10.00")))))
        assertNotEquals(hash,f.hash(8,1,c));assertNotEquals(hash,f.hash(7,2,c))
        assertNotEquals(hash,f.hash(7,1,c.copy(reason="reason")))
        assertNotEquals(hash,f.hash(7,1,c.copy(variantId="other")))
        assertNotEquals(hash,f.hash(7,1,c.copy(line=c.line.copy(productName="Other"))))
        assertNotEquals(hash,f.hash(7,1,SaleCommand.Commit(c.commandId,q,null)))
    }
    @Test fun kindsAreClosedAndAcceptedDoesNotContainTerminalEvidence() {
        SaleCommandKind.entries.forEach { assertEquals(it,SaleCommandKind.fromWire(it.name)) }
        assertEquals(SaleCommandKind.Unknown,SaleCommandKind.fromWire("COMMITTED"))
        assertEquals(SaleCommandKind.Unknown,SaleCommandKind.fromWire(null))
        assertThrows(IllegalArgumentException::class.java) { SaleCommandAdmissionReceipt(UUID.randomUUID(),SaleCommandKind.Unknown,"a".repeat(64),1,1,q,1,1,Instant.EPOCH) }
        val accepted=SaleCommandResult.Accepted(SaleCommandAdmissionReceipt(UUID.randomUUID(),SaleCommandKind.Reserve,"a".repeat(64),1,1,q,1,1,Instant.EPOCH))
        assertEquals(SaleAdmissionOutcomeV2.Accepted,SaleV2ResponseTranslator.translate(accepted).outcome)
        assertFalse(jacksonObjectMapper().findAndRegisterModules().writeValueAsString(SaleV2ResponseTranslator.translate(accepted)).contains("COMMITTED"))
    }
    @Test fun translatorNormalizesOnceAndRejectsPathMismatchAndMalformedRefs() {
        val r=SaleReserveV2Request(UUID.randomUUID(),q.clientInstanceId,q.deviceId,q.saleId,q.operationId,1,"variant",1,"price","sku"," Product ",BigDecimal.TEN,reason=" override ")
        val translated=SaleV2RequestTranslator.reserve(r)
        assertEquals("override",translated.reason);assertEquals(BigDecimal("10.00"),translated.line.originalUnitPrice)
        assertThrows(IllegalArgumentException::class.java) { SaleV2RequestTranslator.reserve(r.copy(clientInstanceId="bad")) }
        assertThrows(IllegalArgumentException::class.java) { SaleV2RequestTranslator.terminal("wrong",SaleTerminalV2Request(r.commandId,q.clientInstanceId,q.deviceId,q.saleId,q.operationId),SaleCommandKind.Commit) }
    }
    @Test fun feePresenceAndBrowserAuthorityAreRejectedIncludingNullAndZero() {
        val mapper=jacksonObjectMapper()
        val capture="""{"commandId":"${UUID.randomUUID()}","clientInstanceId":"${q.clientInstanceId}","deviceId":"device","saleId":"sale","operationId":"${q.operationId}","paymentMethod":"CASH","amount":1"""
        for(value in listOf("null","0","1")) assertThrows(Exception::class.java) { mapper.readValue<PaymentCaptureV2Request>("$capture,\"feeAmount\":$value}") }
        for(field in listOf("actorId","role","lifecycle","writer")) assertThrows(Exception::class.java) { mapper.readValue<SaleTerminalV2Request>("""{"commandId":"${UUID.randomUUID()}","clientInstanceId":"${q.clientInstanceId}","deviceId":"device","saleId":"sale","operationId":"${q.operationId}","$field":null}""") }
    }
    @Test fun lifecyclePermissionDoesNotGrantAuditorSaleOrWorkspace() {
        val policy=StaffAuthorizationPolicy()
        for(role in listOf(StaffRole.CASHIER,StaffRole.SUPERVISOR,StaffRole.OWNER,StaffRole.AUDITOR)) assertTrue(policy.permits(role,StaffPermission.AccountingRuntimeRead))
        assertFalse(policy.permits(StaffRole.UNKNOWN,StaffPermission.AccountingRuntimeRead))
        for(p in listOf(StaffPermission.SaleReserve,StaffPermission.SaleRead,StaffPermission.SaleCommandRead,StaffPermission.WorkspaceRead)) assertFalse(policy.permits(StaffRole.AUDITOR,p))
        assertEquals(StaffPermission.AccountingRuntimeRead,StaffHttpPermission.permission("GET","/api/v2/accounting/runtime"))
        assertEquals(StaffPermission.SaleCommandRead,StaffHttpPermission.permission("GET","/api/v2/sales/commands/id"))
        assertEquals(StaffPermission.SaleCommit,StaffHttpPermission.permission("POST","/api/v2/sales/id/commit"))
    }
    @Test fun lifecycleRejectsUnknownAndIncoherentActivation() {
        assertThrows(IllegalArgumentException::class.java) { AccountingLifecycleObservation(AccountingRuntimeState.Active,null,AccountingContractVersion.V2,Instant.EPOCH) }
        assertThrows(IllegalArgumentException::class.java) { AccountingLifecycleObservation(AccountingRuntimeState.Unknown,null,AccountingContractVersion.V2,Instant.EPOCH) }
    }
}
