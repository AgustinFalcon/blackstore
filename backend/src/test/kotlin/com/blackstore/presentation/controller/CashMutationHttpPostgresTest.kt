package com.blackstore.presentation.controller

import com.blackstore.domain.cash.StaffRole
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.testcontainers.containers.PostgreSQLContainer
import java.util.UUID
import javax.sql.DataSource

@SpringBootTest(properties=["blackstore.persistence.enabled=true","blackstore.identity.loopback-http=true","server.address=127.0.0.1"])
@AutoConfigureMockMvc
class CashMutationHttpPostgresTest {
    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var source: DataSource
    @Autowired private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var counter: com.blackstore.application.counter.CounterApplicationService
    @Autowired private lateinit var cashCommands: com.blackstore.application.cash.CashSessionApplicationService
    private data class Auth(val id: Long,val cookie: Cookie,val csrf: String,val terminal: Long)
    private fun login(role: StaffRole): Auth {
        val login="dct-${UUID.randomUUID()}"
        val ids=source.connection.use { c ->
            val id=c.prepareStatement("INSERT INTO staff_users(login,password_hash,role_code) VALUES(?,?,?) RETURNING id").use { s ->
                s.setString(1,login);s.setString(2,BCryptPasswordEncoder().encode("dct-test-only"));s.setString(3,role.name)
                s.executeQuery().use { r ->r.next();r.getLong(1) }
            }
            val terminal=c.createStatement().executeQuery("INSERT INTO terminals(terminal_code) VALUES('${UUID.randomUUID()}') RETURNING id").use { r ->r.next();r.getLong(1) }
            id to terminal
        }
        val pre=mvc.get("/api/v1/auth/csrf").andExpect { status { isOk() } }.andReturn().response
        val csrf=mapper.readTree(pre.contentAsString)["data"]["csrfToken"].asText()
        val response=mvc.post("/api/v1/auth/login") {
            cookie(pre.getCookie("blackstore-csrf-pre")!!);header("X-CSRF-Token",csrf)
            contentType=MediaType.APPLICATION_JSON;content=mapper.writeValueAsString(mapOf("login" to login,"password" to "dct-test-only"))
        }.andExpect { status { isOk() } }.andReturn().response
        return Auth(ids.first,response.getCookie("blackstore-session-dev")!!,mapper.readTree(response.contentAsString)["data"]["csrfToken"].asText(),ids.second)
    }
    private fun post(auth: Auth,path: String,body: Map<String,Any?>) = mvc.post(path) {
        cookie(auth.cookie);header("X-CSRF-Token",auth.csrf);header("X-Actor-Id","99999");header("X-Role","OWNER")
        contentType=MediaType.APPLICATION_JSON;content=mapper.writeValueAsString(body)
    }.andReturn().response
    private fun opening(auth: Auth,terminal: Long=auth.terminal)=mapOf("terminalId" to terminal,"cashierId" to auth.id,"openingCash" to 0)
    private fun expense(cash: Long,method: String="CASH")=mapOf("cashSessionId" to cash,"category" to "supplies","reason" to "supplies expense","amount" to 1,"method" to method)
    private fun assertError(response: org.springframework.mock.web.MockHttpServletResponse,status: Int,code: String) {
        assertEquals(status,response.status);assertEquals("no-store",response.getHeader("Cache-Control"))
        val body=mapper.readTree(response.contentAsString)
        assertEquals(code,body["errorCode"].asText());assertFalse(body["retryable"].asBoolean());assertTrue(body["data"].isNull)
    }
    @Test fun trustedStaffVisibilityConflictAndValidationHaveSafeHttpEnvelopes() {
        val own=login(StaffRole.CASHIER);val other=login(StaffRole.CASHIER);val owner=login(StaffRole.OWNER);val auditor=login(StaffRole.AUDITOR)
        mvc.post("/api/v1/cash-sessions").andExpect { status { isUnauthorized() } }
        mvc.post("/api/v1/cash-sessions") { cookie(own.cookie);header("X-CSRF-Token","invalid");contentType=MediaType.APPLICATION_JSON;content="{}" }.andExpect { status { isForbidden() } }
        val opened=post(own,"/api/v1/cash-sessions",opening(own));assertEquals(200,opened.status)
        val cash=mapper.readTree(opened.contentAsString)["data"]["id"].asLong()
        assertError(post(own,"/api/v1/cash-sessions",opening(own)),409,"CASH_SESSION_CONFLICT")
        assertError(post(other,"/api/v1/cash-sessions",opening(other,own.terminal)),404,"NOT_FOUND")
        assertError(post(owner,"/api/v1/cash-sessions/$cash/close",mapOf("declared" to 0)),400,"VALIDATION")
        assertError(post(other,"/api/v1/cash-sessions/$cash/close",mapOf("declared" to 0)),404,"NOT_FOUND")
        assertError(post(auditor,"/api/v1/expenses",expense(cash)),403,"FORBIDDEN")
        // HTTP role filtering precedes commands. Exercise the durable application policy separately.
        val permissionDenied=org.junit.jupiter.api.assertThrows<com.blackstore.domain.cash.CashMutationException> {
            counter.addExpense(com.blackstore.domain.identity.AuthenticatedStaff(com.blackstore.domain.identity.StaffUserId(auditor.id),"Auditor",StaffRole.AUDITOR),cash,"supplies",java.math.BigDecimal.ONE,"supplies expense",com.blackstore.domain.sales.PaymentMethod.CASH)
        }
        assertEquals(com.blackstore.domain.cash.CashMutationFailure.Forbidden,permissionDenied.failure)
        assertEquals(com.blackstore.domain.cash.CashRejectionSource.Authorization,permissionDenied.source)
        assertError(post(own,"/api/v1/expenses",expense(cash,"future-method")),400,"VALIDATION")
        assertError(post(own,"/api/v1/expenses",expense(cash).plus("amount" to 0)),400,"VALIDATION")
        val foreignOpen=post(other,"/api/v1/expenses",expense(cash));assertError(foreignOpen,404,"NOT_FOUND")
        val recorded=post(own,"/api/v1/expenses",expense(cash));assertEquals(200,recorded.status)
        val expenseId=mapper.readTree(recorded.contentAsString)["data"]["id"].asLong()
        assertEquals(200,post(own,"/api/v1/cash-sessions/$cash/close",mapOf("declared" to 0)).status)
        assertError(post(own,"/api/v1/cash-sessions/$cash/close",mapOf("declared" to 1)),409,"CASH_SESSION_CONFLICT")
        assertError(post(own,"/api/v1/expenses",expense(cash)),409,"CASH_SESSION_CONFLICT")
        val foreignClosed=post(other,"/api/v1/expenses",expense(cash));val missing=post(other,"/api/v1/expenses",expense(Long.MAX_VALUE))
        for(response in listOf(foreignOpen,foreignClosed,missing)) {
            assertError(response,404,"NOT_FOUND")
            assertEquals("Staff operation denied",mapper.readTree(response.contentAsString)["message"].asText())
        }
        source.connection.use { c ->
            // These independently committed denial rows survive rejected cash transactions.
            for ((actor,expected) in listOf(own.id to 0L,other.id to 5L,owner.id to 1L,auditor.id to 1L)) {
                c.createStatement().executeQuery("SELECT count(*) FROM audit_events WHERE event_type='AUTHORIZATION_DENIED' AND actor_id=$actor").use { r ->r.next();assertEquals(expected,r.getLong(1)) }
            }
            c.createStatement().executeQuery("SELECT count(*),min(created_by) FROM expenses WHERE cash_session_id=$cash").use { r ->r.next();assertEquals(1,r.getLong(1));assertEquals(own.id,r.getLong(2)) }
            c.createStatement().executeQuery("SELECT count(*),min(actor_id) FROM audit_events WHERE aggregate_type='expense' AND aggregate_id=$expenseId").use { r ->r.next();assertEquals(1,r.getLong(1));assertEquals(own.id,r.getLong(2)) }
            c.createStatement().executeQuery("SELECT count(*) FROM audit_events WHERE aggregate_type='cash_session' AND aggregate_id=$cash").use { r ->r.next();assertEquals(2,r.getLong(1)) }
        }
    }
    @Test fun durableInactiveActorAndIneligibleOwnerProduceOneIndependentDenialEach() {
        val actor=login(StaffRole.CASHIER)
        val owner=login(StaffRole.OWNER)
        val ineligible=login(StaffRole.AUDITOR)
        source.connection.use { c ->c.createStatement().execute("UPDATE staff_users SET active=false WHERE id=${actor.id}") }
        val forbidden=org.junit.jupiter.api.assertThrows<com.blackstore.domain.cash.CashMutationException> {
            cashCommands.open(com.blackstore.domain.identity.AuthenticatedStaff(com.blackstore.domain.identity.StaffUserId(actor.id),"Cashier",StaffRole.CASHIER),actor.terminal,actor.id,java.math.BigDecimal.ZERO,null)
        }
        assertEquals(com.blackstore.domain.cash.CashMutationFailure.Forbidden,forbidden.failure)
        assertEquals(com.blackstore.domain.cash.CashRejectionSource.Authorization,forbidden.source)
        val hidden=org.junit.jupiter.api.assertThrows<com.blackstore.domain.cash.CashMutationException> {
            cashCommands.open(com.blackstore.domain.identity.AuthenticatedStaff(com.blackstore.domain.identity.StaffUserId(owner.id),"Owner",StaffRole.OWNER),owner.terminal,ineligible.id,java.math.BigDecimal.ZERO,"override")
        }
        assertEquals(com.blackstore.domain.cash.CashMutationFailure.NotVisible,hidden.failure)
        assertEquals(com.blackstore.domain.cash.CashRejectionSource.Authorization,hidden.source)
        source.connection.use { c ->
            for(id in listOf(actor.id,owner.id)) {
                c.createStatement().executeQuery("SELECT count(*) FROM audit_events WHERE actor_id=$id AND event_type='AUTHORIZATION_DENIED'").use { r ->r.next();assertEquals(1,r.getLong(1)) }
                c.createStatement().executeQuery("SELECT count(*) FROM audit_events WHERE actor_id=$id AND event_type IN ('CASH_SESSION_OPENED','CASH_SESSION_CLOSED','EXPENSE_RECORDED')").use { r ->r.next();assertEquals(0,r.getLong(1)) }
            }
            c.createStatement().executeQuery("SELECT count(*) FROM cash_session_projection WHERE terminal_id IN (${actor.terminal},${owner.terminal})").use { r ->r.next();assertEquals(0,r.getLong(1)) }
        }
    }
    @Test fun supervisorOwnerInactiveAndMissingTargetsHaveOpaque404AndOneIndependentDenial() {
        for(role in listOf(StaffRole.SUPERVISOR,StaffRole.OWNER,StaffRole.CASHIER,null)) {
            val actor=login(StaffRole.OWNER)
            val target=role?.let { login(it) }
            if(role==StaffRole.CASHIER) source.connection.use { c ->c.createStatement().execute("UPDATE staff_users SET active=false WHERE id=${target!!.id}") }
            val response=post(actor,"/api/v1/cash-sessions",mapOf("terminalId" to actor.terminal,"cashierId" to (target?.id ?: Long.MAX_VALUE),"openingCash" to 0,"reason" to "override"))
            assertError(response,404,"NOT_FOUND")
            assertEquals("Staff operation denied",mapper.readTree(response.contentAsString)["message"].asText())
            source.connection.use { c ->
                c.createStatement().executeQuery("SELECT count(*) FROM audit_events WHERE actor_id=${actor.id} AND event_type='AUTHORIZATION_DENIED'").use { r ->r.next();assertEquals(1,r.getLong(1)) }
                c.createStatement().executeQuery("SELECT count(*) FROM audit_events WHERE actor_id=${actor.id} AND event_type IN ('CASH_SESSION_OPENED','CASH_SESSION_CLOSED','EXPENSE_RECORDED')").use { r ->r.next();assertEquals(0,r.getLong(1)) }
                c.createStatement().executeQuery("SELECT count(*) FROM cash_session_projection WHERE terminal_id=${actor.terminal}").use { r ->r.next();assertEquals(0,r.getLong(1)) }
            }
        }
        // A supervisory actor may open for an eligible cashier, but cannot become the target implicitly.
        for(role in listOf(StaffRole.SUPERVISOR,StaffRole.OWNER)) {
            val actor=login(role);val target=login(StaffRole.CASHIER)
            assertEquals(200,post(actor,"/api/v1/cash-sessions",mapOf("terminalId" to actor.terminal,"cashierId" to target.id,"openingCash" to 0,"reason" to "override")).status)
        }
    }
    companion object {
        private val native=System.getenv("DCT_TEST_JDBC_URL")
        private val container by lazy { PostgreSQLContainer<Nothing>("postgres:16-alpine").apply { start() } }
        @DynamicPropertySource @JvmStatic fun properties(registry: DynamicPropertyRegistry) {
            registry.add("blackstore.persistence.url") { native ?: container.jdbcUrl }
            registry.add("blackstore.persistence.username") { if(native!=null) System.getenv("DCT_TEST_ADMIN") ?: "dct_admin" else container.username }
            registry.add("blackstore.persistence.password") { if(native!=null) "" else container.password }
        }
    }
}
