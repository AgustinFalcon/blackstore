package com.blackstore.infrastructure.identity

import com.blackstore.domain.identity.*
import com.blackstore.domain.cash.StaffRole
import com.blackstore.application.identity.*
import com.blackstore.infrastructure.persistence.JdbcBlackStoreWriter
import com.blackstore.domain.model.OperationQuadruple
import org.flywaydb.core.Flyway
import org.postgresql.ds.PGSimpleDataSource
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID
import java.math.BigDecimal

@Testcontainers
class StaffIdentityPostgresTest {
    private val tokens=SecureSessionTokenGenerator()
    private val now=Instant.parse("2026-10-06T12:00:00Z")
    private fun source()=PGSimpleDataSource().also { it.setURL(postgres.jdbcUrl); it.user=postgres.username; it.password=postgres.password }
    private fun repository(): JdbcStaffIdentity { Flyway.configure().dataSource(source()).load().migrate(); return JdbcStaffIdentity(source(),tokens) }
    private fun createStaff(role: StaffRole=StaffRole.CASHIER): StaffUserId {
        repository(); val login="operator-${UUID.randomUUID()}"
        source().connection.use { c -> c.createStatement().execute("INSERT INTO roles(code) VALUES ('CASHIER'),('SUPERVISOR'),('OWNER'),('AUDITOR') ON CONFLICT DO NOTHING")
            return ProvisionLocalStaff(JdbcLocalStaffProvisionPort(c)).execute(StaffProvisionRequest(StaffProvisionOperation.CREATE,login,"Operator",role,"local-test"),login,"controlled-test-secret".toCharArray())
        }
    }
    @Test fun digestOnlySessionSurvivesRepositoryRestartAndRevocationWinsTouch() {
        val repo=repository(); val user=createStaff(); val token=tokens.generate()
        repo.create(StaffSession(tokens.digest(token),user,tokens.generate(),now,now,now.plusSeconds(43200),null))
        val restarted=JdbcStaffIdentity(source(),tokens)
        assertEquals(user,restarted.find(tokens.digest(token))!!.userId)
        assertTrue(restarted.touchIfActive(tokens.digest(token),now.plusSeconds(60)))
        restarted.revoke(tokens.digest(token),now.plusSeconds(61))
        assertFalse(repo.touchIfActive(tokens.digest(token),now.plusSeconds(62)))
        assertEquals(StaffSessionState.REVOKED,repo.find(tokens.digest(token))!!.state(now.plusSeconds(63)))
        assertThrows<java.sql.SQLException> { repo.create(StaffSession(tokens.digest(token),user,tokens.generate(),now,now,now.plusSeconds(43200),null)) }
    }
    @Test fun independentRateBucketsSurviveRestartExpireAndResetOnlyAccountAndPair() {
        val repo=repository(); val login="rate-${UUID.randomUUID()}"; val origin="origin-${UUID.randomUUID()}"
        repeat(4) { repo.failure(login,origin,now) }; assertTrue(repo.allowed(login,origin,now))
        repo.failure(login,origin,now); assertFalse(JdbcStaffIdentity(source(),tokens).allowed(login,origin,now))
        assertTrue(repo.allowed(login,origin,now.plusSeconds(901)))
        repo.success(login,origin); assertTrue(repo.allowed(login,origin,now))
        val globalLogin="distributed-${UUID.randomUUID()}"
        repeat(10) { repo.failure(globalLogin,"distributed-$it-${UUID.randomUUID()}",now) }
        assertFalse(repo.allowed(globalLogin,"new-origin",now))
        val globalOrigin="shared-${UUID.randomUUID()}"
        repeat(30) { repo.failure("many-login-$it-${UUID.randomUUID()}",globalOrigin,now) }
        assertFalse(repo.allowed("new-login",globalOrigin,now))
        repo.success("new-login",globalOrigin); assertFalse(repo.allowed("new-login",globalOrigin,now))
    }
    @Test fun intersectingLoginBucketsAreCoordinatedAcrossRepositoryInstances() {
        val repo=repository(); val other=JdbcStaffIdentity(source(),tokens)
        val origin="concurrent-${UUID.randomUUID()}"; val login="concurrent-${UUID.randomUUID()}"
        repo.coordinate(login,origin) {
            assertEquals(429,assertThrows<StaffSecurityException> { other.coordinate(login,origin) { fail("intersecting login must not enter") } }.status)
            assertEquals(429,assertThrows<StaffSecurityException> { other.coordinate("different-login",origin) { fail("intersecting origin must not enter") } }.status)
            assertTrue(other.coordinate("another-login","another-origin") { true })
        }
        assertTrue(other.coordinate(login,origin) { true })
    }
    @Test fun preauthIsOneUseBoundToOriginAndExpiryAndLimited() {
        val repo=repository(); val origin="csrf-${UUID.randomUUID()}"; val context=repo.issue(origin,now)
        assertFalse(repo.consume(context.cookie,context.csrfToken,"another-origin",now))
        assertFalse(repo.consume(context.cookie,"wrong",origin,now))
        assertTrue(repo.consume(context.cookie,context.csrfToken,origin,now))
        assertFalse(JdbcStaffIdentity(source(),tokens).consume(context.cookie,context.csrfToken,origin,now))
        val expired=repo.issue(origin,now)
        assertFalse(repo.consume(expired.cookie,expired.csrfToken,origin,now.plusSeconds(300)))
        repeat(29) { repo.issue(origin,now) }
        assertThrows<StaffSecurityException> { repo.issue(origin,now) }
    }
    @Test fun ownershipUsesUniquePersistedCashSalePaymentRelations() {
        val repo=repository(); val user=createStaff(); val other=createStaff()
        source().connection.use { c ->
            val terminal=c.createStatement().executeQuery("INSERT INTO terminals(terminal_code) VALUES ('T-${UUID.randomUUID()}') RETURNING id").use { r -> r.next(); r.getLong(1) }
            val writer=JdbcBlackStoreWriter()
            val cash=writer.insertOpenCashSession(c,terminal,user.value,BigDecimal.ZERO,now)
            val client=UUID.randomUUID(); val op=UUID.randomUUID()
            val sale=writer.insertPendingSale(c,client,"device","sale",op,cash,user.value,"1.0.0-draft","a".repeat(64))
            val identity=OperationQuadruple(client.toString(),"device","sale",op.toString())
            val payment=writer.insertPayment(c,sale,"CASH",BigDecimal.ONE,BigDecimal.ZERO,"CAPTURED")
            val action=AuthorizeStaffAction(repo,repo)
            val principal=AuthenticatedStaff(user,"User",StaffRole.CASHIER)
            assertEquals(user,repo.sale(identity)!!.cashierId)
            assertEquals(user,repo.payment(payment,identity)!!.cashierId)
            assertEquals(404,assertThrows<StaffSecurityException> { action.sale(principal.copy(id=other),StaffPermission.SaleRead,op.toString()) }.status)
            assertNull(repo.payment(payment,identity.copy(saleId="other")))
            writer.insertPendingSale(c,client,"another-device","sale",op,cash,user.value,"1.0.0-draft","a".repeat(64))
            assertNull(repo.sale(op.toString()))
            assertEquals(404,assertThrows<StaffSecurityException> { action.sale(principal,StaffPermission.SaleRead,op.toString()) }.status)
        }
    }
    companion object { @Container @JvmStatic val postgres=PostgreSQLContainer<Nothing>("postgres:16-alpine") }
}
