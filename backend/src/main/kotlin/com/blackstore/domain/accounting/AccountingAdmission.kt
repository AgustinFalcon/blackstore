package com.blackstore.domain.accounting

/** Every mutating entry point declares one closed contract boundary. */
enum class AccountingWriterKind { Legacy, VersionTwo, Worker, Unknown }

class AccountingWriterAdmissionPolicy {
    fun failure(state: AccountingRuntimeState, writer: AccountingWriterKind): AccountingCommandFailure? = when {
        state == AccountingRuntimeState.Unknown || writer == AccountingWriterKind.Unknown -> AccountingCommandFailure.Unavailable
        writer == AccountingWriterKind.Legacy && state != AccountingRuntimeState.PreActivation -> AccountingCommandFailure.LegacyContractDisabled
        state == AccountingRuntimeState.Paused -> AccountingCommandFailure.Paused
        writer == AccountingWriterKind.VersionTwo && state == AccountingRuntimeState.PreActivation -> AccountingCommandFailure.NotActivated
        else -> null
    }
}

class AccountingAdmissionException(val failure: AccountingCommandFailure) : RuntimeException("Accounting mutation denied")
