package com.blackstore.domain.cash

import com.blackstore.domain.identity.*
import com.blackstore.domain.sales.MoneyPolicy
import java.math.BigDecimal

enum class CashMutationFailure(val status: Int, val code: String, val label: String) {
    NotVisible(404, "NOT_FOUND", "Staff operation denied"),
    Forbidden(403, "FORBIDDEN", "Staff operation denied"),
    Validation(400, "VALIDATION", "Invalid request"),
    Conflict(409, "CASH_SESSION_CONFLICT", "Cash session is no longer eligible"),
    LegacyContractDisabled(409, "LEGACY_CONTRACT_DISABLED", "Legacy accounting contract is disabled"),
    Unavailable(503, "PERSISTENCE_UNAVAILABLE", "Service unavailable"),
    Unknown(403, "FORBIDDEN", "Staff operation denied");
}
enum class CashRejectionSource { Mutation, Authorization }
class CashMutationException(val failure: CashMutationFailure, val source: CashRejectionSource = CashRejectionSource.Mutation) : RuntimeException(failure.label)
sealed class CashMutationResult<out T> {
    data class Applied<T>(val record: T) : CashMutationResult<T>()
    data class Rejected(val failure: CashMutationFailure, val source: CashRejectionSource = CashRejectionSource.Mutation) : CashMutationResult<Nothing>()
    fun recordOrThrow(): T = when (this) {
        is Applied -> record
        is Rejected -> throw CashMutationException(failure, source)
    }
}

/** Visibility is evaluated before eligibility so a conflict cannot disclose another cashier's session. */
class CashMutationPolicy {
    fun authorize(staff: AuthenticatedStaff, permission: StaffPermission, session: CashSession?, reason: String?): CashMutationFailure? {
        return when (StaffAuthorizationPolicy().decideCashMutation(staff, permission,
            session?.let { OwnedCashSession(it.id, StaffUserId(it.cashierId), it.status) }, reason)) {
            AuthorizationDecision.ALLOW -> null
            AuthorizationDecision.NOT_FOUND -> CashMutationFailure.NotVisible
            AuthorizationDecision.REASON_REQUIRED -> CashMutationFailure.Validation
            AuthorizationDecision.FORBIDDEN -> CashMutationFailure.Forbidden
            AuthorizationDecision.UNKNOWN -> CashMutationFailure.Unknown
        }
    }
    fun requireOpen(session: CashSession) {
        if (session.status != CashSessionStatus.OPEN) throw CashMutationException(CashMutationFailure.Conflict)
    }
    fun money(value: BigDecimal, positive: Boolean = false): BigDecimal {
        if (!MoneyPolicy.valid(value) || value.signum() < 0 || (positive && value.signum() == 0))
            throw CashMutationException(CashMutationFailure.Validation)
        return MoneyPolicy.normalize(value)
    }
}
