package com.blackstore.domain.identity

import com.blackstore.domain.cash.StaffRole
import java.time.Instant

@JvmInline value class StaffUserId(val value: Long)
enum class StaffAccountState { ACTIVE, INACTIVE, UNKNOWN;
    companion object { fun fromWire(raw: String?): StaffAccountState = entries.firstOrNull { it.name == raw } ?: UNKNOWN }
}
enum class StaffSessionState { ACTIVE, EXPIRED, REVOKED, UNKNOWN }
enum class AuthenticationFailure(val securityFailure: StaffSecurityFailure) {
    INVALID_CREDENTIALS(StaffSecurityFailure.INVALID_CREDENTIALS), SESSION_INVALID(StaffSecurityFailure.SESSION_INVALID),
    RATE_LIMITED(StaffSecurityFailure.RATE_LIMITED), ALREADY_AUTHENTICATED(StaffSecurityFailure.ALREADY_AUTHENTICATED), UNKNOWN(StaffSecurityFailure.UNKNOWN)
}
enum class StaffPermission {
    PublicHealthRead, CorsPreflight, CsrfBootstrap, StaffLogin, SessionRead, StaffLogout, CatalogRead, WorkspaceRead,
    CashSessionList, CashSessionOpen, CashSessionClose, SaleReserve, SaleRead, SaleCommit, SaleRelease,
    PaymentCapture, PaymentReverse, ExpenseRecord, ShiftReportRead, DailyReportRead, Unknown
}
enum class AuthorizationDecision { ALLOW, FORBIDDEN, NOT_FOUND, REASON_REQUIRED, UNKNOWN }
enum class SecurityAuditEvent { LOGIN_SUCCEEDED, LOGIN_FAILED, LOGIN_RATE_LIMITED, LOGOUT, AUTHORIZATION_DENIED, STAFF_CREATED, STAFF_RESET, PROVISION_FAILED, PAYMENT_CAPTURED }
data class StaffUser(val id: StaffUserId, val login: String, val displayName: String, val role: StaffRole, val state: StaffAccountState, val passwordHash: String, val credentialVersion: Long = 0)
data class AuthenticatedStaff(val id: StaffUserId, val displayName: String, val role: StaffRole)
data class StaffSession(val digest: String, val userId: StaffUserId, val csrfToken: String, val createdAt: Instant, val lastUsedAt: Instant, val expiresAt: Instant, val revokedAt: Instant?) {
    fun state(now: Instant): StaffSessionState = when {
        revokedAt != null -> StaffSessionState.REVOKED
        !now.isBefore(expiresAt) || !now.isBefore(lastUsedAt.plusSeconds(1800)) -> StaffSessionState.EXPIRED
        else -> StaffSessionState.ACTIVE
    }
}
data class ResolvedStaffSession(val staff: AuthenticatedStaff, val session: StaffSession)
sealed class StaffRequestContext {
    data object Anonymous : StaffRequestContext()
    data class Authenticated(val session: ResolvedStaffSession) : StaffRequestContext()
}
data class OwnedCashSession(val id: Long, val cashierId: StaffUserId, val status: com.blackstore.domain.cash.CashSessionStatus) {
    val open: Boolean get() = status == com.blackstore.domain.cash.CashSessionStatus.OPEN
}

/** Every permission is a positive allowlist. Unknown never inherits a permission. */
class StaffAuthorizationPolicy {
    private val operators = setOf(StaffRole.CASHIER, StaffRole.SUPERVISOR, StaffRole.OWNER)
    private val readers = setOf(StaffRole.SUPERVISOR, StaffRole.OWNER, StaffRole.AUDITOR)
    fun permits(role: StaffRole, permission: StaffPermission): Boolean = when (permission) {
        // Public endpoints use their separate anonymous route context, never a staff role.
        StaffPermission.PublicHealthRead, StaffPermission.CorsPreflight, StaffPermission.CsrfBootstrap, StaffPermission.StaffLogin -> false
        StaffPermission.SessionRead, StaffPermission.StaffLogout -> role in operators || role == StaffRole.AUDITOR
        StaffPermission.CashSessionList, StaffPermission.ShiftReportRead, StaffPermission.DailyReportRead ->
            if (permission == StaffPermission.CashSessionList) role in operators || role == StaffRole.AUDITOR else role in readers
        StaffPermission.CatalogRead, StaffPermission.WorkspaceRead, StaffPermission.CashSessionOpen,
        StaffPermission.CashSessionClose, StaffPermission.SaleReserve, StaffPermission.SaleRead,
        StaffPermission.SaleCommit, StaffPermission.SaleRelease, StaffPermission.PaymentCapture,
        StaffPermission.PaymentReverse, StaffPermission.ExpenseRecord -> role in operators
        StaffPermission.Unknown -> false
    }
    fun decide(staff: AuthenticatedStaff, permission: StaffPermission, cash: OwnedCashSession?, reason: String? = null): AuthorizationDecision =
        decideVisibility(staff, permission, cash, reason).let { decision ->
            if (decision == AuthorizationDecision.ALLOW && permission == StaffPermission.ExpenseRecord && cash?.open != true)
                AuthorizationDecision.NOT_FOUND else decision
        }
    fun decideCashMutation(staff: AuthenticatedStaff, permission: StaffPermission, cash: OwnedCashSession?, reason: String? = null): AuthorizationDecision =
        decideVisibility(staff, permission, cash, reason)
    private fun decideVisibility(staff: AuthenticatedStaff, permission: StaffPermission, cash: OwnedCashSession?, reason: String?): AuthorizationDecision {
        if (!permits(staff.role, permission)) return AuthorizationDecision.FORBIDDEN
        if (cash == null) return AuthorizationDecision.NOT_FOUND
        if (cash.status == com.blackstore.domain.cash.CashSessionStatus.UNKNOWN) return AuthorizationDecision.NOT_FOUND
        val own = cash.cashierId == staff.id
        if (!own && staff.role == StaffRole.CASHIER) return AuthorizationDecision.NOT_FOUND
        val mutation = permission in setOf(StaffPermission.CashSessionOpen, StaffPermission.CashSessionClose, StaffPermission.SaleReserve,
            StaffPermission.SaleCommit, StaffPermission.SaleRelease, StaffPermission.PaymentCapture, StaffPermission.PaymentReverse, StaffPermission.ExpenseRecord)
        if ((permission == StaffPermission.PaymentReverse || (!own && mutation)) && reason.isNullOrBlank()) return AuthorizationDecision.REASON_REQUIRED
        return AuthorizationDecision.ALLOW
    }
}

enum class StaffSecurityFailure(val status: Int) {
    INVALID_CREDENTIALS(401), SESSION_INVALID(401), RATE_LIMITED(429), ALREADY_AUTHENTICATED(409),
    FORBIDDEN(403), NOT_FOUND(404), VALIDATION(400), CSRF_INVALID(403), IDENTITY_UNAVAILABLE(503), UNKNOWN(403)
}
class StaffSecurityException(val failure: StaffSecurityFailure) : RuntimeException("Staff operation denied") {
    val status: Int get() = failure.status
    val errorCode: String get() = failure.name
}
