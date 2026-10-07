package com.blackstore.domain.port.out.accounting

import com.blackstore.domain.accounting.*
import com.blackstore.domain.identity.AuthenticatedStaff
import com.blackstore.domain.sales.MoneyPolicy
import com.blackstore.domain.sales.PaymentMethod
import java.math.BigDecimal
import java.util.UUID

sealed class AccountingMutationOutcome {
    data class Applied(val receipt: AccountingCommandReceipt) : AccountingMutationOutcome()
    data class Rejected(val failure: AccountingCommandFailure) : AccountingMutationOutcome()
}

interface AccountingMutationCommands {
    fun execute(staff: AuthenticatedStaff, command: AccountingCommandDraft): AccountingMutationOutcome
    fun findReceipt(staff: AuthenticatedStaff, commandId: UUID): AccountingCommandResult
}

/** Operational OWNER command; this port is deliberately separate from browser mutations. */
data class PaidFeeCommand(val commandId: UUID, val cashSessionId: Long, val method: PaymentMethod,
    val amount: BigDecimal, val reason: String, val evidenceRef: String) {
    init {
        require(cashSessionId > 0 && method != PaymentMethod.UNKNOWN && amount.signum() > 0)
        require(reason.trim().length in 3..500 && evidenceRef.trim().length in 3..200)
        MoneyPolicy.normalize(amount)
    }
}

interface OperationalAccountingCommands {
    fun recordFee(staff: AuthenticatedStaff, command: PaidFeeCommand): AccountingMutationOutcome
}
