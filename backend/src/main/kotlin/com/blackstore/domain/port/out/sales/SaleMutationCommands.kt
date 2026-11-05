package com.blackstore.domain.port.out.sales

import com.blackstore.domain.identity.AuthenticatedStaff
import com.blackstore.domain.identity.AuthenticatedSessionContext
import com.blackstore.domain.sales.SaleCommand
import com.blackstore.domain.sales.SaleCommandResult
import com.blackstore.domain.sales.SaleAdmissionFamily
import java.util.UUID

interface SaleMutationCommands {
    fun execute(staff: AuthenticatedStaff, command: SaleCommand): SaleCommandResult
    fun execute(context: AuthenticatedSessionContext, command: SaleCommand): SaleCommandResult = execute(context.staff, command)
    fun findReceipt(staff: AuthenticatedStaff, commandId: UUID): SaleCommandResult
    fun findReceipt(staff: AuthenticatedStaff, commandId: UUID, family: SaleAdmissionFamily): SaleCommandResult = findReceipt(staff,commandId)
}
