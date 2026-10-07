package com.blackstore.application.cash

import com.blackstore.domain.cash.CashSession
import com.blackstore.domain.cash.StaffRole
import com.blackstore.domain.port.out.cash.CashSessionStore
import org.springframework.stereotype.Service
import java.math.BigDecimal
import com.blackstore.application.identity.AuthorizeStaffAction
import com.blackstore.domain.identity.*

@Service
class CashSessionApplicationService(
    private val store: CashSessionStore,
    private val authorization: AuthorizeStaffAction,
    private val mutations: com.blackstore.domain.port.out.cash.CashMutationCommands,
) {
    fun list(staff: AuthenticatedStaff): List<CashSession> {
        authorization.permission(staff, StaffPermission.CashSessionList)
        return store.list().filter { staff.role != StaffRole.CASHIER || it.cashierId == staff.id.value }
    }
    fun open(staff: AuthenticatedStaff, terminalId: Long, cashierId: Long, openingCash: BigDecimal, reason: String?): CashSession {
        val result = mutations.open(staff,terminalId,cashierId,openingCash,reason)
        authorization.recordCashDenial(staff,result)
        return result.recordOrThrow()
    }
    fun close(staff: AuthenticatedStaff, sessionId: Long, declared: BigDecimal, reason: String): CashSession {
        val result = mutations.close(staff,sessionId,declared,reason)
        authorization.recordCashDenial(staff,result)
        return result.recordOrThrow()
    }
}
