package com.blackstore.domain

import com.blackstore.domain.accounting.*
import com.blackstore.domain.sales.PaymentMethod
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class ExpenseProjectionEvidencePolicyTest {
    private val id = UUID.randomUUID()
    private val at = Instant.parse("2026-10-08T00:00:00Z")
    private val expense = ExpenseProjectionExpense(3, 2, "rent", BigDecimal.TEN, 1, at)
    private val receipt = AccountingCommandReceipt(id, 1, AccountingCommandKind.EXPENSE_RECORD, 2, "a".repeat(64), listOf(4), at, expenseId = 3)
    private val accrual = ExpenseProjectionPosting(4, id, 2, 3, 1, LedgerPosting(LedgerEventKind.EXPENSE_ACCRUAL,
        LedgerComponent.EXPENSE_ACCRUAL, PaymentMethod.OTHER, BigDecimal.TEN, LedgerOrigin(LedgerOriginKind.EXPENSE, 3)), at, 2)
    private val snapshot = ExpenseProjectionSnapshot(at.plusSeconds(1), at.plusSeconds(1), "snapshot")
    private val policy = ExpenseProjectionEvidencePolicy()
    @Test fun `accrual is complete without claiming paid method`() {
        val found = policy.project(receipt, expense, emptyList(), listOf(accrual), listOf(accrual), snapshot) as ExpenseCommandProjectionResult.Found
        assertEquals(ExpenseProjectionOperation.Accrue, found.projection.operation)
        assertNull(found.projection.expense.paymentMethod)
        assertNull(found.projection.settlement)
    }
    @Test fun `missing duplicate mismatched and foreign source never become Found`() {
        val corruptions = listOf(emptyList(), listOf(accrual, accrual), listOf(accrual.copy(actorId = 8)),
            listOf(accrual.copy(cashSessionId = 8)), listOf(accrual.copy(commandId = UUID.randomUUID())),
            listOf(accrual.copy(accountingVersion = 1)), listOf(accrual.copy(occurredAt = at.minusSeconds(1))),
            listOf(accrual.copy(posting = accrual.posting.copy(amount = BigDecimal.ONE))))
        corruptions.forEach { assertEquals(ExpenseCommandProjectionResult.Unavailable,
            policy.project(receipt, expense, emptyList(), it, listOf(accrual), snapshot)) }
    }
    @Test fun `settlement needs its exact total original accrual and command receipt IDs`() {
        val settleId = UUID.randomUUID()
        val paidAt = at.plusSeconds(1)
        val paid = accrual.copy(id = 5, commandId = settleId, occurredAt = paidAt, posting = LedgerPosting(LedgerEventKind.EXPENSE_PAID,
            LedgerComponent.EXPENSE_SETTLEMENT, PaymentMethod.CARD, BigDecimal.TEN.negate(), LedgerOrigin(LedgerOriginKind.EXPENSE, 3)))
        val settledReceipt = receipt.copy(commandId = settleId, committedAt = paidAt, ledgerEventIds = listOf(5), settlementId = 6)
        val settlement = ExpenseProjectionSettlement(6, 3, 2, BigDecimal.TEN, PaymentMethod.CARD, 1, settleId, paidAt)
        val found = policy.project(settledReceipt, expense, listOf(settlement), listOf(paid), listOf(accrual), snapshot) as ExpenseCommandProjectionResult.Found
        assertEquals(ExpenseProjectionOperation.SettleExisting, found.projection.operation)
        assertEquals(listOf(5L), found.projection.ledgerEventIds)
        assertEquals(ExpenseCommandProjectionResult.Unavailable, policy.project(settledReceipt, expense,
            listOf(settlement.copy(amount = BigDecimal.ONE)), listOf(paid), listOf(accrual), snapshot))
        assertEquals(ExpenseCommandProjectionResult.Unavailable, policy.project(settledReceipt, expense,
            listOf(settlement), listOf(paid), emptyList(), snapshot))
    }
}
