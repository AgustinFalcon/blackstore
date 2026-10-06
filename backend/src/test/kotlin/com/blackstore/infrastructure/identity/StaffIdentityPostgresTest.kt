package com.blackstore.infrastructure.identity

import com.blackstore.domain.identity.*
import com.blackstore.domain.cash.StaffRole
import com.blackstore.application.identity.*
import com.blackstore.infrastructure.persistence.JdbcBlackStoreWriter
import com.blackstore.infrastructure.persistence.JdbcSaleRecordStore
import com.blackstore.domain.sales.*
import com.blackstore.domain.model.StoreCoreOperationKind
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
    @Test fun completedCredentialResetRejectsAnOldPasswordVerificationBeforeSessionIssuance() {
        val repo=repository(); val user=repo.findById(createStaff())!!
        val verified=java.util.concurrent.CountDownLatch(1); val resume=java.util.concurrent.CountDownLatch(1)
        val encoder=org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
        val verifier=object : PasswordVerifier { override fun matches(password: CharArray,hash: String): Boolean {
            val correct=encoder.matches(String(password),hash); assertTrue(correct)
            verified.countDown(); check(resume.await(10,java.util.concurrent.TimeUnit.SECONDS)); return correct
        } }
        val clock=Clock.fixed(now,ZoneOffset.UTC)
        val login=LoginStaff(ValidateLoginAttempt(repo,clock),VerifyStaffCredential(repo,verifier),IssueStaffSession(repo,tokens,clock),RegisterLoginResult(repo,repo,clock),repo)
        val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val result=executor.submit(java.util.concurrent.Callable<Throwable?> { try { login.execute(user.login,"controlled-test-secret".toCharArray(),"race-origin"); null } catch(e: Throwable) { e } })
            assertTrue(verified.await(10,java.util.concurrent.TimeUnit.SECONDS))
            source().connection.use { c -> ProvisionLocalStaff(JdbcLocalStaffProvisionPort(c)).execute(StaffProvisionRequest(StaffProvisionOperation.RESET,user.id.value.toString(),"Operator",StaffRole.CASHIER,"race-test"),user.id.value.toString(),"new-controlled-secret".toCharArray()) }
            assertTrue(repo.findById(user.id)!!.credentialVersion > user.credentialVersion)
            resume.countDown()
            assertEquals(StaffSecurityFailure.INVALID_CREDENTIALS,(result.get(10,java.util.concurrent.TimeUnit.SECONDS) as StaffSecurityException).failure)
            source().connection.use { c -> c.prepareStatement("SELECT count(*) FROM staff_sessions WHERE user_id=?").use { s -> s.setLong(1,user.id.value); s.executeQuery().use { r -> r.next(); assertEquals(0,r.getInt(1)) } } }
            val fresh=VerifiedCredential(repo.findById(user.id)!!)
            assertEquals(user.id,IssueStaffSession(repo,tokens,clock).execute(fresh).resolved.staff.id)
        } finally { resume.countDown(); executor.shutdownNow() }
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
    @Test fun staffCommandAndAuditShareOneTransactionAndRetainActorAndReason() {
        val actor=createStaff(StaffRole.SUPERVISOR); val writer=JdbcBlackStoreWriter(); val store=JdbcSaleRecordStore(source())
        fun fixture(kind: StoreCoreOperationKind, auditActor: StaffUserId): Pair<SaleSaga,Long> {
            // Each fixture owns a distinct cashier's open cash session; audit actor remains independent.
            val cashier=createStaff()
            val identity=OperationQuadruple(UUID.randomUUID().toString(),"audit-device","audit-sale",UUID.randomUUID().toString())
            var cash=0L; var sale=0L
            val evidence=RemoteEvidence(UUID.randomUUID().toString(),"receipt","1.0.0-draft","a".repeat(64),listOf("price-v1"),now.plusSeconds(900))
            source().connection.use { c ->
                val terminal=c.createStatement().executeQuery("INSERT INTO terminals(terminal_code) VALUES ('T-${UUID.randomUUID()}') RETURNING id").use { r -> r.next(); r.getLong(1) }
                cash=writer.insertOpenCashSession(c,terminal,cashier.value,BigDecimal.ZERO,now)
                sale=writer.insertPendingSale(c,UUID.fromString(identity.clientInstanceId),identity.deviceId,identity.saleId,UUID.fromString(identity.operationId),cash,cashier.value,evidence.contractVersion,evidence.openapiDigest)
                writer.markReserved(c,sale,evidence.reservationRef,evidence.receipt,evidence.contractVersion,evidence.openapiDigest,evidence.expiresAt!!)
            }
            val event=if(kind==StoreCoreOperationKind.COMMIT) SaleStaffCommandEvent.COMMIT_REQUESTED else SaleStaffCommandEvent.RELEASE_REQUESTED
            return SaleSaga(identity,cash,if(kind==StoreCoreOperationKind.COMMIT) SaleStatus.COMMIT_PENDING else SaleStatus.RELEASE_PENDING,evidence,
                outbox=listOf(OutboxCommand(identity,kind,"/command",evidence.contractVersion,evidence.openapiDigest,"b".repeat(64))),
                staffCommandAudit=SaleStaffCommandAudit(event,auditActor,"supervisor controlled reason")) to sale
        }
        for(kind in listOf(StoreCoreOperationKind.COMMIT,StoreCoreOperationKind.RELEASE)) {
            val (saga,sale)=fixture(kind,actor)
            if(kind==StoreCoreOperationKind.COMMIT) store.recordCommitPending(saga) else store.recordReleasePending(saga)
            source().connection.use { c -> c.prepareStatement("SELECT actor_id,event_type,payload_redacted->>'detail' FROM audit_events WHERE aggregate_type='sale' AND aggregate_id=?").use { s ->
                s.setLong(1,sale); s.executeQuery().use { r -> assertTrue(r.next()); assertEquals(actor.value,r.getLong(1)); assertEquals(saga.staffCommandAudit!!.event.name,r.getString(2)); assertEquals(saga.staffCommandAudit!!.reason,r.getString(3)); assertFalse(r.next()) }
            }
                c.prepareStatement("SELECT operation_kind FROM storecore_outbox_commands WHERE operation_id=?").use { s -> s.setObject(1,UUID.fromString(saga.quadruple.operationId)); s.executeQuery().use { r -> assertTrue(r.next()); assertEquals(kind.name,r.getString(1)); assertFalse(r.next()) } }
            }
            val (bad,badSale)=fixture(kind,StaffUserId(Long.MAX_VALUE))
            assertThrows<java.sql.SQLException> { if(kind==StoreCoreOperationKind.COMMIT) store.recordCommitPending(bad) else store.recordReleasePending(bad) }
            source().connection.use { c ->
                c.prepareStatement("SELECT count(*) FROM storecore_outbox_commands WHERE operation_id=?").use { s -> s.setObject(1,UUID.fromString(bad.quadruple.operationId)); s.executeQuery().use { r -> r.next(); assertEquals(0,r.getInt(1)) } }
                c.prepareStatement("SELECT count(*) FROM audit_events WHERE aggregate_id=? AND aggregate_type='sale'").use { s -> s.setLong(1,badSale); s.executeQuery().use { r -> r.next(); assertEquals(0,r.getInt(1)) } }
            }
        }
    }
    companion object { @Container @JvmStatic val postgres=PostgreSQLContainer<Nothing>("postgres:16-alpine") }
}
