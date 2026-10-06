package com.blackstore.presentation.controller

import com.blackstore.application.identity.*
import com.blackstore.domain.cash.StaffRole
import com.blackstore.domain.identity.*
import com.blackstore.infrastructure.identity.JdbcLocalStaffProvisionPort
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.http.MediaType
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import javax.sql.DataSource
import java.util.UUID

@SpringBootTest(properties=["blackstore.persistence.enabled=true","blackstore.identity.loopback-http=true","server.address=127.0.0.1"])
@AutoConfigureMockMvc
@Testcontainers
class StaffAuthHttpPostgresTest {
    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var source: DataSource
    @Autowired private lateinit var mapper: ObjectMapper
    private data class Auth(val cookie: Cookie,val csrf: String,val id: Long)
    private fun provision(role: StaffRole): String {
        val login="http-${UUID.randomUUID()}"
        source.connection.use { c -> ProvisionLocalStaff(JdbcLocalStaffProvisionPort(c)).execute(StaffProvisionRequest(StaffProvisionOperation.CREATE,login,"HTTP Operator",role,"http-test"),login,"controlled-test-secret".toCharArray()) }
        return login
    }
    private fun bootstrap(): Pair<Cookie,String> {
        val response=mvc.get("/api/v1/auth/csrf").andExpect { status { isOk() } }.andReturn().response
        val cookie=response.getCookie("blackstore-csrf-pre") ?: error("missing preauth cookie")
        assertTrue(cookie.isHttpOnly); assertEquals("/api/v1/auth",cookie.path)
        return cookie to mapper.readTree(response.contentAsString)["data"]["csrfToken"].asText()
    }
    private fun login(login: String): Auth {
        val pre=bootstrap()
        val response=mvc.post("/api/v1/auth/login") { cookie(pre.first); header("X-CSRF-Token",pre.second); contentType=MediaType.APPLICATION_JSON; content=mapper.writeValueAsString(mapOf("login" to login,"password" to "controlled-test-secret")) }.andExpect { status { isOk() } }.andReturn().response
        val body=mapper.readTree(response.contentAsString)["data"]
        val cookie=response.getCookie("blackstore-session-dev") ?: error("missing session cookie")
        assertTrue(cookie.isHttpOnly); assertEquals("/",cookie.path); assertFalse(cookie.secure)
        return Auth(cookie,body["csrfToken"].asText(),body["staff"]["id"].asLong())
    }
    @Test fun loginCookieCsrfRotationCrossSessionAndLogout() {
        val username=provision(StaffRole.CASHIER)
        val pre=bootstrap()
        mvc.post("/api/v1/auth/login") { cookie(pre.first); header("X-CSRF-Token","invalid"); contentType=MediaType.APPLICATION_JSON; content=mapper.writeValueAsString(mapOf("login" to username,"password" to "controlled-test-secret")) }.andExpect { status { isForbidden() } }
        val response=mvc.post("/api/v1/auth/login") { cookie(pre.first); header("X-CSRF-Token",pre.second); contentType=MediaType.APPLICATION_JSON; content=mapper.writeValueAsString(mapOf("login" to username,"password" to "controlled-test-secret")) }.andExpect { status { isOk() } }.andReturn().response
        val auth=Auth(response.getCookie("blackstore-session-dev")!!,mapper.readTree(response.contentAsString)["data"]["csrfToken"].asText(),mapper.readTree(response.contentAsString)["data"]["staff"]["id"].asLong())
        mvc.post("/api/v1/auth/login") { cookie(pre.first); header("X-CSRF-Token",pre.second); contentType=MediaType.APPLICATION_JSON; content="""{"login":"$username","password":"controlled-test-secret"}""" }.andExpect { status { isForbidden() } }
        mvc.get("/api/v1/auth/session") { cookie(auth.cookie) }.andExpect { status { isOk() }; jsonPath("$.data.staff.id") { value(auth.id) }; jsonPath("$.data.token") { doesNotExist() } }
        mvc.post("/api/v1/auth/login") { cookie(auth.cookie); contentType=MediaType.APPLICATION_JSON; content="""{"login":"$username","password":"controlled-test-secret"}""" }.andExpect { status { isConflict() }; jsonPath("$.errorCode") { value("ALREADY_AUTHENTICATED") } }
        mvc.post("/api/v1/auth/logout") { cookie(auth.cookie); header("X-CSRF-Token",pre.second) }.andExpect { status { isForbidden() } }
        val second=login(username)
        mvc.post("/api/v1/auth/logout") { cookie(auth.cookie); header("X-CSRF-Token",second.csrf) }.andExpect { status { isForbidden() } }
        mvc.post("/api/v1/auth/logout") { cookie(auth.cookie); header("X-CSRF-Token",auth.csrf); header("Origin","https://evil.example") }.andExpect { status { isForbidden() } }
        mvc.post("/api/v1/auth/logout") { cookie(auth.cookie); header("X-CSRF-Token",auth.csrf) }.andExpect { status { isNoContent() }; content { string("") } }
        mvc.get("/api/v1/auth/session") { cookie(auth.cookie) }.andExpect { status { isUnauthorized() } }
        mvc.get("/api/v1/auth/session") { cookie(second.cookie) }.andExpect { status { isOk() } }
        mvc.post("/api/v1/auth/login") { cookie(auth.cookie); contentType=MediaType.APPLICATION_JSON; content="""{"login":"$username","password":"controlled-test-secret"}""" }.andExpect { status { isUnauthorized() }; jsonPath("$.errorCode") { value("SESSION_INVALID") } }
    }
    @Test fun auditorCannotMutateAndForgedHeadersCannotElevateCashier() {
        val cashier=login(provision(StaffRole.CASHIER)); val auditor=login(provision(StaffRole.AUDITOR))
        mvc.get("/api/v1/reports/daily") { cookie(cashier.cookie); header("X-Role","OWNER") }.andExpect { status { isForbidden() } }
        mvc.get("/api/v1/catalog") { cookie(auditor.cookie) }.andExpect { status { isForbidden() } }
        for(path in listOf("/api/v1/cash-sessions","/api/v1/cash-sessions/1/close","/api/v1/sales/reservations","/api/v1/sales/operation/commit","/api/v1/sales/operation/release","/api/v1/payments","/api/v1/payments/1/reversals","/api/v1/expenses")) {
            mvc.post(path) { cookie(auditor.cookie); header("X-CSRF-Token",auditor.csrf); header("X-Role","OWNER") }.andExpect { status { isForbidden() } }
        }
        mvc.get("/api/v1/reports/daily") { cookie(auditor.cookie) }.andExpect { status { isOk() } }
        mvc.post("/api/v1/cash-sessions") { cookie(cashier.cookie); contentType=MediaType.APPLICATION_JSON; content="{}" }.andExpect { status { isForbidden() }; jsonPath("$.errorCode") { value("CSRF_INVALID") } }
        mvc.get("/api/v1/sales/${UUID.randomUUID()}") { cookie(cashier.cookie) }.andExpect { status { isNotFound() } }
        source.connection.use { c -> c.prepareStatement("UPDATE staff_users SET active=FALSE WHERE id=?").use { s -> s.setLong(1,cashier.id); s.executeUpdate() } }
        mvc.get("/api/v1/auth/session") { cookie(cashier.cookie) }.andExpect { status { isUnauthorized() } }
    }
    companion object {
        @Container @JvmStatic val postgres=PostgreSQLContainer<Nothing>("postgres:16-alpine")
        @DynamicPropertySource @JvmStatic fun properties(registry: DynamicPropertyRegistry) { registry.add("blackstore.persistence.url",postgres::getJdbcUrl); registry.add("blackstore.persistence.username",postgres::getUsername); registry.add("blackstore.persistence.password",postgres::getPassword) }
    }
}
