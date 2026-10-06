package com.blackstore.application.identity

import com.blackstore.domain.cash.StaffRole
import com.blackstore.domain.identity.*
import java.time.Clock

data class LoginAttempt(val login: String, val password: CharArray, val origin: String)
data class VerifiedCredential(val user: StaffUser)
data class IssuedStaffSession(val token: String, val resolved: ResolvedStaffSession)

class ValidateLoginAttempt(private val limiter: LoginRateLimit, private val clock: Clock) {
    fun execute(attempt: LoginAttempt) {
        if (attempt.login.length !in 1..160 || attempt.password.size !in 1..256) throw StaffSecurityException(StaffSecurityFailure.INVALID_CREDENTIALS)
        if (!limiter.allowed(attempt.login, attempt.origin, clock.instant())) throw StaffSecurityException(StaffSecurityFailure.RATE_LIMITED)
    }
}
class VerifyStaffCredential(private val users: StaffUserRepository, private val verifier: PasswordVerifier) {
    // Valid, unrelated BCrypt hash. Unknown accounts still pay the verification cost.
    private val dummy = "\$2a\$10\$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"
    fun execute(attempt: LoginAttempt): VerifiedCredential {
        val user = users.findByLogin(attempt.login)
        val valid = verifier.matches(attempt.password, user?.passwordHash ?: dummy)
        if (!valid || user == null || user.state != StaffAccountState.ACTIVE || user.role == StaffRole.UNKNOWN) throw StaffSecurityException(StaffSecurityFailure.INVALID_CREDENTIALS)
        return VerifiedCredential(user)
    }
}
class IssueStaffSession(private val sessions: StaffSessionRepository, private val tokens: SessionTokenGenerator, private val clock: Clock) {
    fun execute(credential: VerifiedCredential): IssuedStaffSession {
        val now = clock.instant()
        val token = tokens.generate()
        val session = StaffSession(tokens.digest(token), credential.user.id, tokens.generate(), now, now, now.plusSeconds(43200), null)
        sessions.create(session)
        return IssuedStaffSession(token, ResolvedStaffSession(credential.user.toPrincipal(), session))
    }
}
class RegisterLoginResult(private val limiter: LoginRateLimit, private val audit: SecurityAuditPort, private val clock: Clock) {
    fun success(attempt: LoginAttempt, staff: AuthenticatedStaff) { limiter.success(attempt.login, attempt.origin); audit.record(SecurityAuditEvent.LOGIN_SUCCEEDED, staff.id) }
    fun failure(attempt: LoginAttempt, exception: StaffSecurityException) {
        if (exception.failure == StaffSecurityFailure.RATE_LIMITED) audit.record(SecurityAuditEvent.LOGIN_RATE_LIMITED, null)
        else { limiter.failure(attempt.login, attempt.origin, clock.instant()); audit.record(SecurityAuditEvent.LOGIN_FAILED, null) }
    }
}
class LoginStaff(private val validate: ValidateLoginAttempt, private val verify: VerifyStaffCredential, private val issue: IssueStaffSession, private val register: RegisterLoginResult, private val coordinator: LoginAttemptCoordinator) {
    fun execute(login: String, password: CharArray, origin: String): IssuedStaffSession {
        val attempt = LoginAttempt(login.trim().lowercase(java.util.Locale.ROOT), password, origin)
        var recorded=false
        try {
            return coordinator.coordinate(attempt.login,attempt.origin) {
                try { validate.execute(attempt); val result = issue.execute(verify.execute(attempt)); register.success(attempt, result.resolved.staff); recorded=true; result }
                catch (e: StaffSecurityException) { register.failure(attempt,e); recorded=true; throw e }
            }
        } catch (e: StaffSecurityException) { if(!recorded) register.failure(attempt,e); throw e }
        finally { password.fill('\u0000') }
    }
}
class ResolveStaffSession(private val users: StaffUserRepository, private val sessions: StaffSessionRepository, private val tokens: SessionTokenGenerator, private val clock: Clock) {
    fun execute(token: String?): ResolvedStaffSession {
        if (token == null || token.length != 43) throw StaffSecurityException(StaffSecurityFailure.SESSION_INVALID)
        val session = sessions.find(tokens.digest(token)) ?: throw StaffSecurityException(StaffSecurityFailure.SESSION_INVALID)
        val user = users.findById(session.userId)
        if (session.state(clock.instant()) != StaffSessionState.ACTIVE || user == null || user.state != StaffAccountState.ACTIVE || user.role == StaffRole.UNKNOWN || !sessions.touchIfActive(session.digest, clock.instant())) throw StaffSecurityException(StaffSecurityFailure.SESSION_INVALID)
        return ResolvedStaffSession(user.toPrincipal(), session)
    }
}
class LogoutStaff(private val sessions: StaffSessionRepository, private val audit: SecurityAuditPort, private val clock: Clock) {
    fun execute(session: ResolvedStaffSession) { sessions.revoke(session.session.digest, clock.instant()); audit.record(SecurityAuditEvent.LOGOUT, session.staff.id) }
}
private fun StaffUser.toPrincipal() = AuthenticatedStaff(id, displayName, role)
