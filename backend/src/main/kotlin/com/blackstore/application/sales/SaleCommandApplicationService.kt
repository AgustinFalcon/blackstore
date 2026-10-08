package com.blackstore.application.sales

import com.blackstore.domain.identity.AuthenticatedStaff
import com.blackstore.domain.port.out.sales.SaleMutationCommands
import com.blackstore.domain.sales.*
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class SaleCommandApplicationService(private val ports: ObjectProvider<SaleMutationCommands>) {
    fun execute(staff: AuthenticatedStaff, command: SaleCommand): SaleCommandResult = ports.ifAvailable?.execute(staff,command) ?: SaleCommandResult.Unavailable
    fun receipt(staff: AuthenticatedStaff, id: UUID): SaleCommandResult = ports.ifAvailable?.findReceipt(staff,id) ?: SaleCommandResult.Unavailable
}
