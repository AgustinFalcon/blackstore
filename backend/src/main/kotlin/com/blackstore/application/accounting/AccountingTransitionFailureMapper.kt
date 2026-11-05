package com.blackstore.application.accounting

import com.blackstore.domain.accounting.AccountingCommandFailure
import com.blackstore.domain.sales.TransitionReason

/** Exhaustive translation from a closed domain denial into the public accounting failure vocabulary. */
object AccountingTransitionFailureMapper {
    fun translate(reason: TransitionReason): AccountingCommandFailure = when (reason) {
        TransitionReason.SaleMissingOrAmbiguous -> AccountingCommandFailure.NotVisible
        TransitionReason.MoneyInvalid -> AccountingCommandFailure.Validation
        TransitionReason.StateIneligible,
        TransitionReason.Overcapture,
        TransitionReason.CoverageIncomplete,
        TransitionReason.PaymentHistory,
        TransitionReason.OriginalPaymentMismatch -> AccountingCommandFailure.TransitionConflict
        TransitionReason.StateUnavailable,
        TransitionReason.EvidenceInvalid,
        TransitionReason.LedgerUnknown,
        TransitionReason.CommandInvalid -> AccountingCommandFailure.Unavailable
    }
}
