package com.blackstore.domain.identity

import com.blackstore.domain.model.OperationQuadruple
import java.time.Instant

interface StaffUserRepository { fun findByLogin(login: String): StaffUser?; fun findById(id: StaffUserId): StaffUser? }
interface StaffSessionRepository {
    fun create(session: StaffSession)
    fun createIfCredentialCurrent(session: StaffSession, verified: StaffUser): Boolean
    fun find(digest: String): StaffSession?
    fun touchIfActive(digest: String, now: Instant): Boolean
    fun revoke(digest: String, now: Instant)
}
interface PasswordVerifier { fun matches(password: CharArray, hash: String): Boolean }
interface SessionTokenGenerator { fun generate(): String; fun digest(token: String): String }
interface SecurityAuditPort { fun record(event: SecurityAuditEvent, actor: StaffUserId?, target: StaffUserId? = null) }
interface LoginRateLimit { fun allowed(login: String, origin: String, now: Instant): Boolean; fun failure(login: String, origin: String, now: Instant); fun success(login: String, origin: String) }
interface PreAuthenticationContexts { fun issue(origin: String, now: Instant): PreAuthenticationContext; fun consume(cookie: String, csrf: String, origin: String, now: Instant): Boolean }
data class PreAuthenticationContext(val cookie: String, val csrfToken: String, val expiresAt: Instant)
interface StaffOwnershipQuery {
    fun cash(id: Long): OwnedCashSession?
    fun sale(operationId: String): OwnedCashSession?
    fun sale(identity: OperationQuadruple): OwnedCashSession?
    fun payment(paymentId: Long, identity: OperationQuadruple): OwnedCashSession?
    fun eligibleCashier(id: StaffUserId): Boolean
}
interface LoginAttemptCoordinator { fun <T> coordinate(login: String, origin: String, action: () -> T): T }
interface StaffIdentityRepository : StaffUserRepository, StaffSessionRepository, SecurityAuditPort, LoginRateLimit, PreAuthenticationContexts, StaffOwnershipQuery, LoginAttemptCoordinator
