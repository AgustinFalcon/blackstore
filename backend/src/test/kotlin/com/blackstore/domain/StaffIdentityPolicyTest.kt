package com.blackstore.domain

import com.blackstore.domain.cash.StaffRole
import com.blackstore.domain.identity.*
import com.blackstore.application.identity.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class StaffIdentityPolicyTest {
    private val policy=StaffAuthorizationPolicy()
    private val cash=OwnedCashSession(20,StaffUserId(1),com.blackstore.domain.cash.CashSessionStatus.OPEN)
    @Test fun closedRolesPermissionsAndOwnership() {
        assertEquals(StaffRole.UNKNOWN,StaffRole.fromWire("administrator"))
        assertEquals(StaffAccountState.UNKNOWN,StaffAccountState.fromWire("new-state"))
        for(role in StaffRole.entries) for(permission in StaffPermission.entries) {
            if(role==StaffRole.UNKNOWN && permission !in setOf(StaffPermission.PublicHealthRead,StaffPermission.CsrfBootstrap,StaffPermission.StaffLogin)) assertFalse(policy.permits(role,permission))
            if(permission==StaffPermission.Unknown) assertFalse(policy.permits(role,permission))
        }
        val cashier=AuthenticatedStaff(StaffUserId(1),"A",StaffRole.CASHIER)
        val foreign=cash.copy(cashierId=StaffUserId(2))
        for(permission in listOf(StaffPermission.CashSessionClose,StaffPermission.SaleRead,StaffPermission.SaleReserve,StaffPermission.PaymentCapture,StaffPermission.PaymentReverse,StaffPermission.ExpenseRecord)) {
            assertEquals(AuthorizationDecision.NOT_FOUND,policy.decide(cashier,permission,foreign,"reason"))
            assertEquals(AuthorizationDecision.NOT_FOUND,policy.decide(cashier,permission,null,"reason"))
            assertEquals(AuthorizationDecision.FORBIDDEN,policy.decide(cashier.copy(role=StaffRole.AUDITOR),permission,cash,"reason"))
        }
        assertEquals(AuthorizationDecision.REASON_REQUIRED,policy.decide(cashier.copy(role=StaffRole.SUPERVISOR),StaffPermission.SaleCommit,foreign))
        assertEquals(AuthorizationDecision.ALLOW,policy.decide(cashier.copy(role=StaffRole.OWNER),StaffPermission.SaleCommit,foreign,"override"))
        assertEquals(AuthorizationDecision.ALLOW,policy.decide(cashier,StaffPermission.SaleRead,cash))
        assertEquals(AuthorizationDecision.REASON_REQUIRED,policy.decide(cashier,StaffPermission.PaymentReverse,cash))
        assertEquals(AuthorizationDecision.NOT_FOUND,policy.decide(cashier,StaffPermission.ExpenseRecord,cash.copy(status=com.blackstore.domain.cash.CashSessionStatus.CLOSED),"expense"))
        assertEquals(AuthorizationDecision.NOT_FOUND,policy.decide(cashier,StaffPermission.SaleReserve,cash.copy(status=com.blackstore.domain.cash.CashSessionStatus.UNKNOWN),"reserve"))
    }
    @Test fun sessionAbsoluteIdleAndRevocation() {
        val now=Instant.parse("2026-10-06T12:00:00Z")
        val session=StaffSession("digest",StaffUserId(1),"csrf",now,now,now.plusSeconds(43200),null)
        assertEquals(StaffSessionState.ACTIVE,session.state(now.plusSeconds(1799)))
        assertEquals(StaffSessionState.EXPIRED,session.state(now.plusSeconds(1800)))
        assertEquals(StaffSessionState.EXPIRED,session.copy(lastUsedAt=now.plusSeconds(43199)).state(now.plusSeconds(43200)))
        assertEquals(StaffSessionState.REVOKED,session.copy(revokedAt=now).state(now))
    }
    @Test fun credentialsDenyUniformlyClearSecretAndUseCurrentRole() {
        val fixture=TestIdentity()
        val clock=Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"),ZoneOffset.UTC)
        val verifier=object : PasswordVerifier { override fun matches(password: CharArray,hash: String)=hash=="valid" && String(password)=="correct-secret" }
        val tokens=object : SessionTokenGenerator { override fun generate()="x".repeat(43); override fun digest(token: String)="digest" }
        val login=LoginStaff(ValidateLoginAttempt(fixture,clock),VerifyStaffCredential(fixture,verifier),IssueStaffSession(fixture,tokens,clock),RegisterLoginResult(fixture,fixture,clock),fixture)
        for(user in listOf(null,fixture.user.copy(state=StaffAccountState.INACTIVE),fixture.user.copy(role=StaffRole.UNKNOWN),fixture.user.copy(passwordHash="malformed"))) {
            fixture.current=user; val secret="correct-secret".toCharArray()
            val e=assertThrows<StaffSecurityException> { login.execute("  CASHIER ",secret,"127.0.0.1") }
            assertEquals(401,e.status); assertEquals("INVALID_CREDENTIALS",e.errorCode); assertTrue(secret.all { it=='\u0000' })
        }
        fixture.current=fixture.user
        val issued=login.execute("cashier","correct-secret".toCharArray(),"127.0.0.1")
        val resolver=ResolveStaffSession(fixture,fixture,tokens,clock)
        assertEquals(fixture.user.id,resolver.execute(issued.token).staff.id)
        fixture.current=fixture.user.copy(role=StaffRole.AUDITOR)
        assertEquals(StaffRole.AUDITOR,resolver.execute(issued.token).staff.role)
        LogoutStaff(fixture,fixture,clock).execute(issued.resolved)
        assertThrows<StaffSecurityException> { resolver.execute(issued.token) }
    }
    @Test fun provisionConfirmsTargetBeforeEffect() {
        var effects=0
        val usecase=ProvisionLocalStaff(object : LocalStaffProvisionPort { override fun execute(request: StaffProvisionRequest,password: CharArray): StaffUserId { effects++; return StaffUserId(1) } })
        val request=StaffProvisionRequest(StaffProvisionOperation.CREATE,"operator","Operator",StaffRole.CASHIER,"local")
        val password="correct-secret".toCharArray()
        assertThrows<IllegalArgumentException> { usecase.execute(request,"other",password) }
        assertEquals(0,effects); assertTrue(password.all { it=='\u0000' })
        usecase.execute(request,"operator","correct-secret".toCharArray()); assertEquals(1,effects)
    }
    private class TestIdentity : StaffUserRepository,StaffSessionRepository,SecurityAuditPort,LoginRateLimit,LoginAttemptCoordinator {
        override fun <T> coordinate(login: String,origin: String,action: () -> T): T = action()
        val user=StaffUser(StaffUserId(1),"cashier","Cashier",StaffRole.CASHIER,StaffAccountState.ACTIVE,"valid")
        var current: StaffUser?=user
        private var session: StaffSession?=null
        override fun findByLogin(login: String)=current
        override fun findById(id: StaffUserId)=current
        override fun create(session: StaffSession) { this.session=session }
        override fun createIfCredentialCurrent(session: StaffSession,verified: StaffUser): Boolean {
            if(current != verified) return false
            create(session); return true
        }
        override fun find(digest: String)=session
        override fun touchIfActive(digest: String,now: Instant)=session?.state(now)==StaffSessionState.ACTIVE
        override fun revoke(digest: String,now: Instant) { session=session?.copy(revokedAt=now) }
        override fun record(event: SecurityAuditEvent,actor: StaffUserId?,target: StaffUserId?)=Unit
        override fun allowed(login: String,origin: String,now: Instant)=true
        override fun failure(login: String,origin: String,now: Instant)=Unit
        override fun success(login: String,origin: String)=Unit
    }
}
