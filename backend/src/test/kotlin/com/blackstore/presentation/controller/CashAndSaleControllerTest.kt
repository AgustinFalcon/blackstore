package com.blackstore.presentation.controller

import com.jayway.jsonpath.JsonPath
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
class CashAndSaleControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun cashierOpensOwnSessionAndAuditorIsRejected() {
        mockMvc
            .post("/api/v1/cash-sessions") {
                header("X-Actor-Id", "7")
                header("X-Role", "CASHIER")
                header("X-Trace-Id", "trace-cash")
                contentType = MediaType.APPLICATION_JSON
                content = """{"terminalId":10,"cashierId":7,"openingCash":0}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.code") { value(200) }
                jsonPath("$.data.status") { value("OPEN") }
                jsonPath("$.errorCode") { value(nullValue()) }
            }

        mockMvc
            .post("/api/v1/cash-sessions") {
                header("X-Actor-Id", "9")
                header("X-Role", "AUDITOR")
                contentType = MediaType.APPLICATION_JSON
                content = """{"terminalId":11,"cashierId":9,"openingCash":0}"""
            }.andExpect {
                status { isForbidden() }
                jsonPath("$.errorCode") { value("FORBIDDEN") }
                jsonPath("$.data") { value(null) }
            }
    }

    @Test
    fun catalogAndShiftReportAreReadable() {
        mockMvc.get("/api/v1/catalog").andExpect {
            status { isOk() }
            jsonPath("$.data.stale") { value(false) }
            jsonPath("$.data.version") { value("fixture-v1") }
        }
        mockMvc.get("/api/v1/reports/shift").andExpect {
            status { isOk() }
            jsonPath("$.data.fiscalResult") { value(false) }
            jsonPath("$.data.margin") { value(nullValue()) }
            jsonPath("$.data.periodKind") { value("SHIFT") }
        }
        mockMvc.get("/api/v1/reports/daily").andExpect {
            status { isOk() }
            jsonPath("$.data.periodKind") { value("DAY") }
            jsonPath("$.data.fiscalResult") { value(false) }
        }
        mockMvc.get("/api/v1/workspace").andExpect {
            status { isOk() }
            jsonPath("$.data.persistence") { value("memory") }
        }
    }

    @Test
    fun fixtureReserveReturnsReceiptWithoutHttp() {
        mockMvc
            .post("/api/v1/sales/reservations") {
                header("X-Trace-Id", "trace-sale")
                contentType = MediaType.APPLICATION_JSON
                content =
                    """
                    {
                      "clientInstanceId": "11111111-1111-1111-1111-111111111111",
                      "deviceId": "terminal-1",
                      "saleId": "sale-http",
                      "operationId": "op-http",
                      "cashSessionId": 1,
                      "variantId": "variant-1",
                      "quantity": 1,
                      "expectedPriceVersion": "price-v1"
                    }
                    """.trimIndent()
            }.andExpect {
                status { isOk() }
                jsonPath("$.data.status") { value("RESERVED") }
                jsonPath("$.data.receipt") { value("rcpt-op-http") }
                jsonPath("$.errorCode") { value(nullValue()) }
            }
    }

    @Test
    fun cashierClosesOwnSessionWithAnAuditReason() {
        val opened =
            mockMvc
                .post("/api/v1/cash-sessions") {
                    header("X-Actor-Id", "7")
                    header("X-Role", "CASHIER")
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"terminalId":12,"cashierId":7,"openingCash":20}"""
                }.andExpect {
                    status { isOk() }
                }.andReturn()
        val sessionId = JsonPath.read<Int>(opened.response.contentAsString, "$.data.id")
        mockMvc
            .post("/api/v1/cash-sessions/$sessionId/close") {
                header("X-Actor-Id", "7")
                header("X-Role", "CASHIER")
                contentType = MediaType.APPLICATION_JSON
                content = """{"declared":20,"reason":"cierre de turno"}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.data.status") { value("CLOSED") }
                jsonPath("$.data.closingCashDeclared") { value(20) }
            }
    }

    @Test
    fun reversalAddsARefundAndLeavesTheCapturedPayment() {
        val captured =
            mockMvc
                .post("/api/v1/payments") {
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"operationId":"op-pay","method":"CASH","amount":10,"feeAmount":1}"""
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.data.status") { value("CAPTURED") }
                }.andReturn()
        val paymentId = JsonPath.read<Int>(captured.response.contentAsString, "$.data.id")
        mockMvc
            .post("/api/v1/payments/$paymentId/reversals") {
                header("X-Actor-Id", "7")
                header("X-Role", "CASHIER")
                contentType = MediaType.APPLICATION_JSON
                content = """{"operationId":"op-pay","reason":"cliente devolvio","evidenceRef":"rcpt-op-pay"}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.data.status") { value("REFUNDED") }
                jsonPath("$.data.id") { value(org.hamcrest.Matchers.not(paymentId)) }
            }
    }
}
