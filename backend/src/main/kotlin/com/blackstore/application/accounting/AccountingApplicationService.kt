package com.blackstore.application.accounting

import com.blackstore.domain.accounting.AccountingCommandDraft
import com.blackstore.domain.accounting.AccountingCommandResult
import com.blackstore.domain.accounting.AccountingCommandFailure
import com.blackstore.domain.identity.AuthenticatedStaff
import com.blackstore.domain.port.out.accounting.AccountingMutationCommands
import com.blackstore.domain.port.out.accounting.AccountingMutationOutcome
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import java.util.UUID

/** The transaction port owns admission, current authority and receipt visibility. */
@Service
class AccountingApplicationService(private val commands: ObjectProvider<AccountingMutationCommands>) {
    fun execute(staff: AuthenticatedStaff, command: AccountingCommandDraft): AccountingMutationOutcome =
        commands.ifAvailable?.execute(staff, command)
            ?: AccountingMutationOutcome.Rejected(AccountingCommandFailure.Unavailable)

    fun receipt(staff: AuthenticatedStaff, commandId: UUID): AccountingCommandResult =
        commands.ifAvailable?.findReceipt(staff, commandId) ?: AccountingCommandResult.Unavailable
}
