package com.blackstore.presentation.controller

import com.blackstore.application.sales.LocalSaleSagaService
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
class AuthenticatedCommercialRegressionTest {
    @Autowired private lateinit var context: org.springframework.web.context.WebApplicationContext
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("springSecurityFilterChain") private lateinit var securityChain: jakarta.servlet.Filter
    @org.springframework.boot.test.mock.mockito.MockBean private lateinit var resolver: com.blackstore.application.identity.ResolveStaffSession
    @org.springframework.boot.test.mock.mockito.MockBean private lateinit var authorizer: com.blackstore.application.identity.AuthorizeStaffAction
    @org.junit.jupiter.api.BeforeEach fun trustedTestSession() {
        val now=java.time.Instant.now()
        val staff=com.blackstore.domain.identity.AuthenticatedStaff(com.blackstore.domain.identity.StaffUserId(7),"Regression Operator",com.blackstore.domain.cash.StaffRole.CASHIER)
        val identity=com.blackstore.domain.model.OperationQuadruple("11111111-1111-1111-1111-111111111111","terminal-1","sale-http-policy","op-http-policy")
        for(permission in listOf(com.blackstore.domain.identity.StaffPermission.SaleCommit,com.blackstore.domain.identity.StaffPermission.SaleRelease)) {
            org.mockito.Mockito.`when`(authorizer.sale(staff,permission,identity,null)).thenReturn(com.blackstore.domain.identity.OwnedCashSession(1,staff.id,com.blackstore.domain.cash.CashSessionStatus.OPEN))
        }
        org.mockito.Mockito.`when`(resolver.execute(org.mockito.ArgumentMatchers.anyString())).thenAnswer { invocation ->
            val token=invocation.getArgument<String>(0)
            val role=when(token) {
                "a".repeat(43) -> com.blackstore.domain.cash.StaffRole.AUDITOR
                "o".repeat(43) -> com.blackstore.domain.cash.StaffRole.OWNER
                else -> com.blackstore.domain.cash.StaffRole.CASHIER
            }
            com.blackstore.domain.identity.ResolvedStaffSession(
                com.blackstore.domain.identity.AuthenticatedStaff(com.blackstore.domain.identity.StaffUserId(7),"Regression Operator",role),
                com.blackstore.domain.identity.StaffSession("test-digest",com.blackstore.domain.identity.StaffUserId(7),"c".repeat(43),now,now,now.plusSeconds(43200),null))
        }
        mockMvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
            .addFilters<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(securityChain)
            .defaultRequest<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/")
                .cookie(jakarta.servlet.http.Cookie("__Host-blackstore-session","s".repeat(43))).header("X-CSRF-Token","c".repeat(43)))
            .build()
    }


    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var localSaleSagaService: LocalSaleSagaService

    @Test
    fun cashierOpensOwnSessionAndAuditorIsRejected() {
        mockMvc
            .post("/api/v1/cash-sessions") {
                
                
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
                
                cookie(jakarta.servlet.http.Cookie("__Host-blackstore-session","a".repeat(43)))
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
            jsonPath("$.data.items[0].sku") { value("SKU-1") }
            jsonPath("$.data.items[0].name") { value("Café molido 500 g") }
            jsonPath("$.data.items.length()") { value(5) }
            jsonPath("$.data.items[1].sku") { value("SKU-YERBA-1K") }
            jsonPath("$.data.items[1].priceVersion") { value("price-demo-1") }
            jsonPath("$.data.items[4].unitPrice") { value(3290.0) }
        }
        mockMvc.get("/api/v1/reports/shift") { cookie(jakarta.servlet.http.Cookie("__Host-blackstore-session","o".repeat(43))) }.andExpect {
            status { isOk() }
            jsonPath("$.data.fiscalResult") { value(false) }
            jsonPath("$.data.margin") { value(nullValue()) }
            jsonPath("$.data.periodKind") { value("SHIFT") }
        }
        mockMvc.get("/api/v1/reports/daily") { cookie(jakarta.servlet.http.Cookie("__Host-blackstore-session","o".repeat(43))) }.andExpect {
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
        val catalog = mockMvc.get("/api/v1/catalog").andReturn()
        val expectedPriceVersion = JsonPath.read<String>(catalog.response.contentAsString, "$.data.items[0].priceVersion")

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
                      "expectedPriceVersion": "$expectedPriceVersion"
                    }
                    """.trimIndent()
            }.andExpect {
                status { isOk() }
                jsonPath("$.data.status") { value("RESERVED") }
                jsonPath("$.data.receipt") { value("rcpt-op-http") }
                jsonPath("$.errorCode") { value(nullValue()) }
            }
        org.junit.jupiter.api.Assertions.assertEquals(
            listOf(expectedPriceVersion),
            localSaleSagaService.stored("op-http")?.evidence?.acceptedPriceVersions,
        )
        mockMvc.get("/api/v1/sales/op-http").andExpect {
            status { isOk() }
            jsonPath("$.data.status") { value("RESERVED") }
            jsonPath("$.data.operationId") { value("op-http") }
            jsonPath("$.errorCode") { value(nullValue()) }
        }
        mockMvc.get("/api/v1/sales/missing-op").andExpect {
            status { isNotFound() }
            jsonPath("$.errorCode") { value("NOT_FOUND") }
            jsonPath("$.data") { value(null) }
            jsonPath("$.retryable") { value(false) }
        }
    }

    @Test
    fun cashierClosesOwnSessionWithAnAuditReason() {
        val opened =
            mockMvc
                .post("/api/v1/cash-sessions") {
                    
                    
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"terminalId":12,"cashierId":7,"openingCash":20}"""
                }.andExpect {
                    status { isOk() }
                }.andReturn()
        val sessionId = JsonPath.read<Int>(opened.response.contentAsString, "$.data.id")
        mockMvc
            .post("/api/v1/cash-sessions/$sessionId/close") {
                
                
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
        reservePaymentTicket("op-pay", "sale-pay")
        val captured =
            mockMvc
                .post("/api/v1/payments") {
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"clientInstanceId":"11111111-1111-1111-1111-111111111111","deviceId":"terminal-1","saleId":"sale-pay","operationId":"op-pay","method":"CASH","amount":10,"feeAmount":1}"""
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.data.status") { value("CAPTURED") }
                }.andReturn()
        val paymentId = JsonPath.read<Int>(captured.response.contentAsString, "$.data.paymentId")
        mockMvc
            .post("/api/v1/payments/$paymentId/reversals") {
                
                
                contentType = MediaType.APPLICATION_JSON
                content = """{"clientInstanceId":"11111111-1111-1111-1111-111111111111","deviceId":"terminal-1","saleId":"sale-pay","operationId":"op-pay","reason":"cliente devolvio","evidenceRef":"rcpt-op-pay"}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.data.status") { value("REFUNDED") }
                jsonPath("$.data.paymentId") { value(org.hamcrest.Matchers.not(paymentId)) }
            }
    }

    private fun reservePaymentTicket(operation: String, saleId: String) {
        mockMvc.post("/api/v1/sales/reservations") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"clientInstanceId":"11111111-1111-1111-1111-111111111111","deviceId":"terminal-1","saleId":"$saleId","operationId":"$operation","cashSessionId":1,"variantId":"variant-1","quantity":1,"expectedPriceVersion":"price-demo-1","sku":"SKU-1","productName":"Cafe","originalUnitPrice":18,"discountAmount":0}"""
        }.andExpect { status { isOk() }; jsonPath("$.data.paymentCoverage") { value("UNPAID") }; jsonPath("$.data.totalAmount") { value(18.0) } }
    }

    @Test fun t11HttpCannotBypassPaymentTransitionPolicy() {
        val identity = """"clientInstanceId":"11111111-1111-1111-1111-111111111111","deviceId":"terminal-1","saleId":"sale-http-policy","operationId":"op-http-policy""""
        mockMvc.post("/api/v1/payments") { contentType = MediaType.APPLICATION_JSON; content = """{$identity,"method":"CASH","amount":10,"feeAmount":0}""" }.andExpect { status { isBadRequest() } }
        reservePaymentTicket("op-http-policy", "sale-http-policy")
        mockMvc.post("/api/v1/sales/op-http-policy/commit").andExpect { status { isBadRequest() } }
        for (method in listOf("FUTURE", "", "{}")) {
            mockMvc.post("/api/v1/payments") { contentType = MediaType.APPLICATION_JSON; content = """{$identity,"method":"$method","amount":10,"feeAmount":0}""" }.andExpect { status { isBadRequest() } }
        }
        mockMvc.post("/api/v1/payments") { contentType = MediaType.APPLICATION_JSON; content = """{$identity,"method":null,"amount":10,"feeAmount":0}""" }.andExpect { status { isBadRequest() } }
        mockMvc.post("/api/v1/payments") { contentType = MediaType.APPLICATION_JSON; content = """{$identity,"method":{},"amount":10,"feeAmount":0}""" }.andExpect { status { isBadRequest() }; jsonPath("$.errorCode") { value("VALIDATION") } }
        val payment = mockMvc.post("/api/v1/payments") { contentType = MediaType.APPLICATION_JSON; content = """{$identity,"method":"CASH","amount":10,"feeAmount":0}""" }.andExpect {
            status { isOk() }; jsonPath("$.data.operationId") { value("op-http-policy") }; jsonPath("$.data.saleId") { value("sale-http-policy") }; jsonPath("$.data.paymentId") { exists() }
        }.andReturn()
        val id = JsonPath.read<Int>(payment.response.contentAsString, "$.data.paymentId")
        mockMvc.post("/api/v1/sales/op-http-policy/release").andExpect { status { isBadRequest() } }
        mockMvc.post("/api/v1/sales/op-http-policy/commit").andExpect { status { isBadRequest() } }
        mockMvc.post("/api/v1/payments/$id/reversals") {
            ; ; contentType = MediaType.APPLICATION_JSON
            content = """{"clientInstanceId":"11111111-1111-1111-1111-111111111111","deviceId":"terminal-1","saleId":"wrong-sale","operationId":"op-http-policy","reason":"return","evidenceRef":"ev"}"""
        }.andExpect { status { isBadRequest() } }
    }
}
