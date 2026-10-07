package com.blackstore.application.accounting

import com.blackstore.domain.accounting.AccountingCommandFailure
import com.blackstore.domain.accounting.AccountingRuntimeState
import com.blackstore.domain.cash.CashSession
import com.blackstore.domain.cash.CashSessionStatus
import com.blackstore.domain.identity.AuthenticatedStaff
import com.blackstore.domain.identity.AuthorizationDecision
import com.blackstore.domain.identity.OwnedCashSession
import com.blackstore.domain.identity.StaffAuthorizationPolicy
import com.blackstore.domain.identity.StaffPermission
import com.blackstore.domain.identity.StaffUserId

/** Read/replay visibility is independent from mutation eligibility and deliberately permits CLOSED sessions. */
class AccountingReceiptAccessPolicy {
    fun authorize(staff: AuthenticatedStaff, cash: CashSession?): AccountingCommandFailure? {
        if (cash?.status == CashSessionStatus.UNKNOWN) return AccountingCommandFailure.NotVisible
        return when (StaffAuthorizationPolicy().decideCashMutation(
            staff,
            StaffPermission.AccountingCommandRead,
            cash?.let { OwnedCashSession(it.id, StaffUserId(it.cashierId), it.status) },
        )) {
            AuthorizationDecision.ALLOW -> null
            AuthorizationDecision.FORBIDDEN -> AccountingCommandFailure.Forbidden
            AuthorizationDecision.NOT_FOUND,
            AuthorizationDecision.REASON_REQUIRED -> AccountingCommandFailure.NotVisible
            AuthorizationDecision.UNKNOWN -> AccountingCommandFailure.Unavailable
        }
    }
}

/** A new V2 mutation is admitted only while the additive accounting runtime is ACTIVE. */
class AccountingLifecycleAdmissionStep {
    fun admit(state: AccountingRuntimeState): AccountingCommandFailure? = when (state) {
        AccountingRuntimeState.Active -> null
        AccountingRuntimeState.PreActivation -> AccountingCommandFailure.NotActivated
        AccountingRuntimeState.Paused -> AccountingCommandFailure.Paused
        AccountingRuntimeState.Unknown -> AccountingCommandFailure.Unavailable
    }
}
