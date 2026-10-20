package com.blackstore.presentation.controller

import com.blackstore.application.accounting.*
import com.blackstore.domain.accounting.*
import com.blackstore.domain.cash.StaffRole
import com.blackstore.domain.identity.*
import com.blackstore.infrastructure.exception.GlobalExceptionHandler
import com.blackstore.infrastructure.identity.StaffSessionFilter
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.UUID

class ExpenseCommandProjectionControllerTest {
    private val staff = AuthenticatedStaff(StaffUserId(7), "Cashier", StaffRole.CASHIER)
    private val session = ResolvedStaffSession(staff, StaffSession("digest", staff.id, "csrf", Instant.EPOCH, Instant.EPOCH, Instant.MAX, null))
    private val id = UUID.randomUUID()
    @Test fun `closed failures preserve opaque envelope no store and only call query`() {
        for ((result, expected) in listOf(ExpenseCommandProjectionResult.NotFound to ExpenseProjectionState.NotFound,
            ExpenseCommandProjectionResult.Unavailable to ExpenseProjectionState.Unavailable, ExpenseCommandProjectionResult.Unknown to ExpenseProjectionState.Unknown)) {
            var calls = 0
            val port = ExpenseCommandProjectionQuery { received, command ->
                assertEquals(session, received); assertEquals(id, command); calls++; result
            }
            val mvc = mvc(port)
            val response = mvc.perform(get("/api/v2/expenses/commands/$id/projection")
                .requestAttr(StaffSessionFilter.SESSION_ATTRIBUTE, session)).andReturn().response
            assertEquals(expected.status, response.status)
            assertEquals("no-store", response.getHeader("Cache-Control"))
            val data = jacksonObjectMapper().readTree(response.contentAsString)["data"]
            assertEquals(expected.wire, data["state"].textValue())
            assertTrue(data["projection"].isNull)
            assertEquals(1, calls)
        }
    }
    @Test fun `invalid UUID unauthenticated and forbidden cannot expose projection`() {
        val port = ExpenseCommandProjectionQuery { _, _ -> throw StaffSecurityException(StaffSecurityFailure.FORBIDDEN) }
        val mvc = mvc(port)
        for ((path, authenticated, status) in listOf(Triple("invalid", true, 400), Triple(id.toString(), false, 401), Triple(id.toString(), true, 403))) {
            val request = get("/api/v2/expenses/commands/$path/projection")
            if (authenticated) request.requestAttr(StaffSessionFilter.SESSION_ATTRIBUTE, session)
            val response = mvc.perform(request).andReturn().response
            assertEquals(status, response.status)
            assertEquals("no-store", response.getHeader("Cache-Control"))
        }
    }
    @Test fun `durable payment JSON preserves nullable original reference`() {
        val mapper = jacksonObjectMapper()
        val capture = DurableSalePaymentResponse(1, com.blackstore.domain.sales.PaymentStatus.CAPTURED,
            com.blackstore.domain.sales.PaymentMethod.CASH, java.math.BigDecimal.TEN, java.math.BigDecimal.ZERO)
        assertTrue(mapper.readTree(mapper.writeValueAsString(capture))["originalPaymentId"].isNull)
        val refund = capture.copy(paymentId = 2, status = com.blackstore.domain.sales.PaymentStatus.REFUNDED, originalPaymentId = 1)
        assertEquals(1, mapper.readTree(mapper.writeValueAsString(refund))["originalPaymentId"].longValue())
    }
    private fun mvc(port: ExpenseCommandProjectionQuery) = MockMvcBuilders.standaloneSetup(ExpenseCommandProjectionController(
        ExpenseCommandProjectionService(StaticListableBeanFactory(mapOf("query" to port)).getBeanProvider(ExpenseCommandProjectionQuery::class.java))))
        .setControllerAdvice(GlobalExceptionHandler()).build()
}
