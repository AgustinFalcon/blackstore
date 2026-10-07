package com.blackstore.presentation.controller

import com.blackstore.application.accounting.AccountingApplicationService
import com.blackstore.application.dto.accounting.AccountingCommandOutcomeV2
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
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.UUID

class AccountingV2ControllerTest {
    private val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val staff = AuthenticatedStaff(StaffUserId(7), "Cashier", StaffRole.CASHIER)
    private val session = ResolvedStaffSession(staff, StaffSession("digest", staff.id, "csrf", Instant.EPOCH, Instant.EPOCH, Instant.MAX, null))
    private val port = RecordingCommands()
    private val mvc = MockMvcBuilders.standaloneSetup(AccountingV2Controller(AccountingApplicationService(
        StaticListableBeanFactory(mapOf("commands" to port)).getBeanProvider(AccountingMutationCommands::class.java))))
        .setControllerAdvice(GlobalExceptionHandler()).build()
    private val mapper = jacksonObjectMapper()

    @Test fun `post translators pass SID authority and UUID command to transaction port`() {
        val requests = listOf(
            "/api/v2/cash-sessions" to """{"commandId":"$id","terminalId":1,"cashierId":7,"openingCash":0}""",
            "/api/v2/cash-sessions/2/close" to """{"commandId":"$id","cashSessionId":2,"declaredCash":0,"reason":"close shift"}""",
            "/api/v2/expenses" to """{"commandId":"$id","cashSessionId":2,"operation":"ACCRUE","category":"supplies","amount":10,"reason":"supplies"}""",
            "/api/v2/payments" to """{"commandId":"$id","clientInstanceId":"client","deviceId":"device","saleId":"sale","operationId":"operation","paymentMethod":"CASH","amount":10,"reason":"override"}""",
            "/api/v2/payments/3/reversals" to """{"commandId":"$id","clientInstanceId":"client","deviceId":"device","saleId":"sale","operationId":"operation","originalPaymentId":3,"reason":"reverse","evidenceRef":"evidence"}""",
        )
        requests.forEach { (path, body) ->
            val response = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)
                .requestAttr(StaffSessionFilter.SESSION_ATTRIBUTE, session)).andReturn().response
            assertEquals(200, response.status)
            assertEquals("no-store", response.getHeader("Cache-Control"))
            assertEquals(id, port.lastCommand?.commandId)
            assertEquals(staff, port.lastStaff)
            assertEquals(AccountingCommandOutcomeV2.Committed.wire, mapper.readTree(response.contentAsString)["data"]["outcome"].asText())
            (port.lastCommand as? AccountingCommandDraft.PaymentCapture)?.let { assertEquals("override", it.reason) }
        }
        assertEquals(5, port.executions)
    }

    @Test fun `bad UUID unknown method and reversal path mismatch do not execute`() {
        val bodies = listOf(
            """{"commandId":"invalid","terminalId":1,"cashierId":7,"openingCash":0}""" to "/api/v2/cash-sessions",
            """{"terminalId":1,"cashierId":7,"openingCash":0}""" to "/api/v2/cash-sessions",
            """{"commandId":"$id","clientInstanceId":"client","deviceId":"device","saleId":"sale","operationId":"operation","paymentMethod":"alien","amount":10}""" to "/api/v2/payments",
            """{"commandId":"$id","clientInstanceId":"client","deviceId":"device","saleId":"sale","operationId":"operation","originalPaymentId":3,"reason":"reverse","evidenceRef":"evidence"}""" to "/api/v2/payments/4/reversals",
            """{"commandId":"$id","cashSessionId":2,"declaredCash":0,"reason":"close shift"}""" to "/api/v2/cash-sessions/3/close",
        )
        bodies.forEach { (body, path) ->
            assertEquals(400, mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)
                .requestAttr(StaffSessionFilter.SESSION_ATTRIBUTE, session)).andReturn().response.status)
        }
        assertEquals(0, port.executions)
    }

    @Test fun `all transaction failures use closed status safe message and no store`() {
        val statuses = mapOf(
            AccountingCommandFailure.NotVisible to 404, AccountingCommandFailure.Forbidden to 403,
            AccountingCommandFailure.Validation to 400, AccountingCommandFailure.Closed to 409,
            AccountingCommandFailure.TransitionConflict to 409, AccountingCommandFailure.CashSessionConflict to 409,
            AccountingCommandFailure.NonTerminalSale to 409, AccountingCommandFailure.PayloadMismatch to 409,
            AccountingCommandFailure.LegacyContractDisabled to 409, AccountingCommandFailure.NotActivated to 409,
            AccountingCommandFailure.Paused to 409, AccountingCommandFailure.Unavailable to 503, AccountingCommandFailure.Unknown to 503)
        assertEquals(AccountingCommandFailure.entries.toSet(), statuses.keys)
        statuses.forEach { (failure, status) ->
            port.next = AccountingMutationOutcome.Rejected(failure)
            val response = mvc.perform(post("/api/v2/cash-sessions").contentType(MediaType.APPLICATION_JSON)
                .content("""{"commandId":"$id","terminalId":1,"cashierId":7,"openingCash":0}""")
                .requestAttr(StaffSessionFilter.SESSION_ATTRIBUTE, session)).andReturn().response
            assertEquals(status, response.status)
            assertEquals("no-store", response.getHeader("Cache-Control"))
            val json = mapper.readTree(response.contentAsString)
            assertEquals(failure.wire, json["data"]["failure"].asText())
            assertFalse(json["retryable"].asBoolean())
        }
    }

    @Test fun `receipt lookup cannot execute commands and preserves unavailable and opaque not found`() {
        for ((result, status) in listOf(AccountingCommandResult.Committed(port.receipt) to 200,
            AccountingCommandResult.NotFound to 404, AccountingCommandResult.Unavailable to 503, AccountingCommandResult.Unknown to 503)) {
            port.queryResult = result
            val response = mvc.perform(get("/api/v2/accounting/commands/$id")
                .requestAttr(StaffSessionFilter.SESSION_ATTRIBUTE, session)).andReturn().response
            assertEquals(status, response.status)
            assertEquals("no-store", response.getHeader("Cache-Control"))
            assertFalse(response.contentAsString.contains(port.receipt.payloadHash))
        }
        assertEquals(0, port.executions)
        assertEquals(4, port.queries)
    }

    @Test fun `absent transaction bean fails closed`() {
        val unavailable = AccountingApplicationService(StaticListableBeanFactory().getBeanProvider(AccountingMutationCommands::class.java))
        assertEquals(AccountingCommandResult.Unavailable, unavailable.receipt(staff, id))
        assertEquals(AccountingMutationOutcome.Rejected(AccountingCommandFailure.Unavailable),
            unavailable.execute(staff, AccountingCommandDraft.CashSessionOpen(id, 1, 7, java.math.BigDecimal.ZERO, null)))
    }

    @Test fun `close receipt publishes typed reconciliation and nullable legacy amounts`() {
        val snapshot = CashCloseSnapshot(java.math.BigDecimal.TEN, null, null, ReconciliationOutcome.Unavailable,
            AccountingCoverage.LegacyIncomplete, Instant.EPOCH, 0)
        port.next = AccountingMutationOutcome.Applied(port.receipt.copy(kind = AccountingCommandKind.CASH_SESSION_CLOSE,
            ledgerEventIds = emptyList(), closeSnapshot = snapshot))
        val response = mvc.perform(post("/api/v2/cash-sessions/2/close").contentType(MediaType.APPLICATION_JSON)
            .content("""{"commandId":"$id","cashSessionId":2,"declaredCash":10,"reason":"close shift"}""")
            .requestAttr(StaffSessionFilter.SESSION_ATTRIBUTE, session)).andReturn().response
        assertEquals(200, response.status)
        val result = mapper.readTree(response.contentAsString)["data"]["closeSnapshot"]
        assertEquals(com.blackstore.application.dto.accounting.ReconciliationOutcomeV2.Unavailable.wire, result["outcome"].asText())
        assertEquals(com.blackstore.application.dto.accounting.AccountingCoverageV2.LegacyIncomplete.wire, result["coverage"].asText())
        assertTrue(result["expectedCash"].isNull && result["difference"].isNull)
    }

    private class RecordingCommands : AccountingMutationCommands {
        val receipt = AccountingCommandReceipt(UUID.fromString("00000000-0000-0000-0000-000000000001"), 7, AccountingCommandKind.CASH_SESSION_OPEN, 2, "a".repeat(64), listOf(3), Instant.EPOCH)
        var next: AccountingMutationOutcome = AccountingMutationOutcome.Applied(receipt)
        var queryResult: AccountingCommandResult = AccountingCommandResult.Committed(receipt)
        var lastCommand: AccountingCommandDraft? = null
        var lastStaff: AuthenticatedStaff? = null
        var executions = 0
        var queries = 0
        override fun execute(staff: AuthenticatedStaff, command: AccountingCommandDraft): AccountingMutationOutcome {
            executions++; lastCommand = command; lastStaff = staff; return next
        }
        override fun findReceipt(staff: AuthenticatedStaff, commandId: UUID): AccountingCommandResult {
            queries++; lastStaff = staff; assertEquals(receipt.commandId, commandId); return queryResult
        }
    }
}
