package com.blackstore.application.accounting

import com.blackstore.domain.accounting.AccountingCommandFailure
import com.blackstore.domain.accounting.AccountingRuntimeState
import com.blackstore.domain.cash.CashSession
import com.blackstore.domain.cash.CashSessionStatus
import com.blackstore.domain.cash.StaffRole
import com.blackstore.domain.identity.AuthenticatedStaff
import com.blackstore.domain.identity.StaffUserId
import com.blackstore.domain.sales.TransitionReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class AccountingMutationStepsTest {
    private val openedAt = Instant.parse("2026-10-07T12:00:00Z")
    private fun staff(id: Long, role: StaffRole) = AuthenticatedStaff(StaffUserId(id), "staff-$id", role)
    private fun cash(owner: Long, status: CashSessionStatus = CashSessionStatus.OPEN) =
        CashSession(10, 20, owner, openedAt, BigDecimal.ZERO, status,
            closedAt = if (status == CashSessionStatus.CLOSED) openedAt.plusSeconds(60) else null,
            closingCashDeclared = if (status == CashSessionStatus.CLOSED) BigDecimal.ZERO else null)

    @Test fun `receipt visibility is current ownership based and permits closed sessions`() {
        val policy = AccountingReceiptAccessPolicy()
        assertNull(policy.authorize(staff(7, StaffRole.CASHIER), cash(7, CashSessionStatus.CLOSED)))
        assertEquals(AccountingCommandFailure.NotVisible, policy.authorize(staff(7, StaffRole.CASHIER), cash(8)))
        assertNull(policy.authorize(staff(9, StaffRole.SUPERVISOR), cash(8)))
        assertEquals(AccountingCommandFailure.Forbidden, policy.authorize(staff(9, StaffRole.AUDITOR), cash(8)))
        assertEquals(AccountingCommandFailure.NotVisible, policy.authorize(staff(7, StaffRole.CASHIER), null))
        assertEquals(AccountingCommandFailure.Forbidden, policy.authorize(staff(7, StaffRole.UNKNOWN), cash(7)))
    }

    @Test fun `lifecycle step admits only active v2 mutations`() {
        val step = AccountingLifecycleAdmissionStep()
        assertNull(step.admit(AccountingRuntimeState.Active))
        assertEquals(AccountingCommandFailure.NotActivated, step.admit(AccountingRuntimeState.PreActivation))
        assertEquals(AccountingCommandFailure.Paused, step.admit(AccountingRuntimeState.Paused))
        assertEquals(AccountingCommandFailure.Unavailable, step.admit(AccountingRuntimeState.Unknown))
    }

    @Test fun `transition failure mapping is exhaustive and status safe`() {
        val expected = mapOf(
            TransitionReason.SaleMissingOrAmbiguous to AccountingCommandFailure.NotVisible,
            TransitionReason.MoneyInvalid to AccountingCommandFailure.Validation,
            TransitionReason.StateIneligible to AccountingCommandFailure.TransitionConflict,
            TransitionReason.Overcapture to AccountingCommandFailure.TransitionConflict,
            TransitionReason.CoverageIncomplete to AccountingCommandFailure.TransitionConflict,
            TransitionReason.PaymentHistory to AccountingCommandFailure.TransitionConflict,
            TransitionReason.OriginalPaymentMismatch to AccountingCommandFailure.TransitionConflict,
            TransitionReason.StateUnavailable to AccountingCommandFailure.Unavailable,
            TransitionReason.EvidenceInvalid to AccountingCommandFailure.Unavailable,
            TransitionReason.LedgerUnknown to AccountingCommandFailure.Unavailable,
            TransitionReason.CommandInvalid to AccountingCommandFailure.Unavailable,
        )
        assertEquals(TransitionReason.entries.toSet(), expected.keys)
        expected.forEach { (reason, failure) -> assertEquals(failure, AccountingTransitionFailureMapper.translate(reason)) }
    }
}
