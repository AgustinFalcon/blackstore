package com.blackstore.domain.port.out.cash

import com.blackstore.domain.cash.CashMutationResult
import com.blackstore.domain.cash.CashSession
import com.blackstore.domain.identity.AuthenticatedStaff
import com.blackstore.domain.ledger.ExpenseRecord
import java.math.BigDecimal
import java.time.Instant

/** Each adapter owns the complete command, including its audit and current authority check. */
interface CashMutationCommands {
    fun open(staff: AuthenticatedStaff, terminalId: Long, cashierId: Long, amount: BigDecimal, reason: String?, now: Instant = Instant.now()): CashMutationResult<CashSession>
    fun close(staff: AuthenticatedStaff, sessionId: Long, amount: BigDecimal, reason: String?, now: Instant = Instant.now()): CashMutationResult<CashSession>
    fun expense(staff: AuthenticatedStaff, expense: ExpenseRecord): CashMutationResult<ExpenseRecord>
}
