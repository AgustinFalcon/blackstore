package com.blackstore.application.sales

import com.blackstore.domain.accounting.AccountingRuntimeState
import com.blackstore.domain.cash.*
import com.blackstore.domain.identity.*
import com.blackstore.domain.sales.*

/** Shared by admission and receipt recovery; read access never requires OPEN or a new reason. */
class SaleReceiptAuthorityPolicy {
    fun failure(actor: AuthenticatedStaff,kind: SaleCommandKind,cash: OwnedCashSession,reason: String?,mutation: Boolean): SaleCommandFailure? = when {
        !StaffAuthorizationPolicy().permits(actor.role,kind.permission) -> SaleCommandFailure.Forbidden
        cash.status==CashSessionStatus.UNKNOWN || (actor.role==StaffRole.CASHIER && cash.cashierId!=actor.id) -> SaleCommandFailure.NotVisible
        mutation && cash.cashierId!=actor.id && reason.isNullOrBlank() -> SaleCommandFailure.Validation
        else -> null
    }
}
class SaleReceiptReplayPolicy {
    fun failure(command: SaleCommand,saved: SaleCommandAdmissionReceipt,hash: String): SaleCommandFailure? =
        if(saved.kind!=command.kind || saved.identity!=command.identity || saved.payloadHash!=hash) SaleCommandFailure.PayloadMismatch else null
}
class SaleLifecycleAdmissionStep {
    fun failure(state: AccountingRuntimeState,open: Boolean): SaleCommandFailure?=when(state) {
        AccountingRuntimeState.PreActivation -> SaleCommandFailure.NotActivated
        AccountingRuntimeState.Paused -> SaleCommandFailure.Paused
        AccountingRuntimeState.Unknown -> SaleCommandFailure.Unavailable
        AccountingRuntimeState.Active -> if(open) null else SaleCommandFailure.Closed
    }
}
