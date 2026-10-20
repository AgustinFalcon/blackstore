package com.blackstore.domain.port.out.sales

import com.blackstore.domain.identity.AuthenticatedStaff
import com.blackstore.domain.sales.SaleCommand
import com.blackstore.domain.sales.SaleCommandResult
import java.util.UUID

interface SaleMutationCommands {
    fun execute(staff: AuthenticatedStaff, command: SaleCommand): SaleCommandResult
    fun findReceipt(staff: AuthenticatedStaff, commandId: UUID): SaleCommandResult
}
