package com.blackstore.domain.accounting

import com.blackstore.domain.sales.MoneyPolicy
import com.blackstore.domain.sales.PaymentMethod
import com.blackstore.domain.reports.DataCompleteness
import com.blackstore.domain.reports.CompletenessCause
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class ExpenseProjectionOperation { Accrue, AccrueAndSettle, SettleExisting, Unknown }
data class ExpenseProjectionExpense(val id: Long, val cashSessionId: Long, val category: String, val amount: BigDecimal,
    val actorId: Long, val createdAt: Instant, val paymentMethod: PaymentMethod? = null)
data class ExpenseProjectionSettlement(val id: Long, val expenseId: Long, val cashSessionId: Long, val amount: BigDecimal,
    val paymentMethod: PaymentMethod, val actorId: Long, val commandId: UUID, val paidAt: Instant)
data class ExpenseProjectionPosting(val id: Long, val commandId: UUID, val cashSessionId: Long, val expenseId: Long?,
    val actorId: Long, val posting: LedgerPosting, val occurredAt: Instant, val accountingVersion: Int)
data class ExpenseProjectionSnapshot(val cutoff: Instant, val asOf: Instant, val id: String)
data class ExpenseProjectionEvidence(val committedAt: Instant, val postings: List<ExpenseProjectionPosting>,
    val snapshot: ExpenseProjectionSnapshot, val accountingVersion: Int = 2,
    val completeness: DataCompleteness = DataCompleteness.Complete,
    val causes: List<CompletenessCause> = emptyList())
data class ExpenseCommandProjection(val commandId: UUID, val kind: AccountingCommandKind, val operation: ExpenseProjectionOperation,
    val cashSessionId: Long, val expenseId: Long, val settlementId: Long?, val ledgerEventIds: List<Long>,
    val expense: ExpenseProjectionExpense, val settlement: ExpenseProjectionSettlement?, val accountingEvidence: ExpenseProjectionEvidence)
sealed class ExpenseCommandProjectionResult {
    data class Found(val projection: ExpenseCommandProjection) : ExpenseCommandProjectionResult()
    data object NotFound : ExpenseCommandProjectionResult()
    data object Unavailable : ExpenseCommandProjectionResult()
    data object Unknown : ExpenseCommandProjectionResult()
}

/** Validates source facts, never synthesizes missing postings or attributes later settlement to an accrual. */
class ExpenseProjectionEvidencePolicy {
    fun project(receipt: AccountingCommandReceipt, expense: ExpenseProjectionExpense,
        settlements: List<ExpenseProjectionSettlement>, postings: List<ExpenseProjectionPosting>,
        originalAccruals: List<ExpenseProjectionPosting>, snapshot: ExpenseProjectionSnapshot): ExpenseCommandProjectionResult {
        val unavailable = ExpenseCommandProjectionResult.Unavailable
        if (receipt.kind != AccountingCommandKind.EXPENSE_RECORD || receipt.saleId != null || receipt.paymentId != null ||
            receipt.closeSnapshot != null || receipt.expenseId != expense.id || expense.id <= 0 || expense.cashSessionId != receipt.cashSessionId ||
            expense.actorId <= 0 || expense.category.isBlank() || !MoneyPolicy.valid(expense.amount) || expense.amount.signum() <= 0 ||
            expense.paymentMethod != null || receipt.committedAt > snapshot.cutoff || snapshot.id.isBlank()) return unavailable
        if (postings.map { it.id }.toSet() != receipt.ledgerEventIds.toSet() || postings.size != receipt.ledgerEventIds.size ||
            postings.map { it.id }.distinct().size != postings.size || postings.any {
                it.commandId != receipt.commandId || it.cashSessionId != receipt.cashSessionId || it.expenseId != expense.id ||
                    it.actorId != receipt.actorId || it.accountingVersion != 2 || it.occurredAt != receipt.committedAt ||
                    it.posting.origin != LedgerOrigin(LedgerOriginKind.EXPENSE, expense.id)
            }) return unavailable
        val accrual = postings.filter { it.posting.kind == LedgerEventKind.EXPENSE_ACCRUAL }
        val paid = postings.filter { it.posting.kind == LedgerEventKind.EXPENSE_PAID }
        if (accrual.size > 1 || paid.size > 1 || accrual.size + paid.size != postings.size) return unavailable
        val operation = when {
            accrual.size == 1 && paid.isEmpty() && receipt.settlementId == null -> ExpenseProjectionOperation.Accrue
            accrual.size == 1 && paid.size == 1 && receipt.settlementId != null -> ExpenseProjectionOperation.AccrueAndSettle
            accrual.isEmpty() && paid.size == 1 && receipt.settlementId != null -> ExpenseProjectionOperation.SettleExisting
            else -> return unavailable
        }
        val origin = originalAccruals.singleOrNull() ?: return unavailable
        if (origin.expenseId != expense.id || origin.cashSessionId != expense.cashSessionId || origin.actorId != expense.actorId ||
            origin.occurredAt != expense.createdAt || origin.accountingVersion != 2 || origin.posting.kind != LedgerEventKind.EXPENSE_ACCRUAL ||
            origin.posting.origin != LedgerOrigin(LedgerOriginKind.EXPENSE, expense.id) || origin.posting.amount.compareTo(expense.amount) != 0) return unavailable
        if (operation != ExpenseProjectionOperation.SettleExisting && (origin.commandId != receipt.commandId || expense.actorId != receipt.actorId)) return unavailable
        if (operation == ExpenseProjectionOperation.SettleExisting && (origin.commandId == receipt.commandId || origin.occurredAt > receipt.committedAt)) return unavailable
        val settlement = if (receipt.settlementId == null) null else settlements.singleOrNull() ?: return unavailable
        if (receipt.settlementId == null && settlements.isNotEmpty()) return unavailable
        if (settlement != null && (settlement.id != receipt.settlementId || settlement.expenseId != expense.id ||
            settlement.cashSessionId != receipt.cashSessionId || settlement.commandId != receipt.commandId || settlement.actorId != receipt.actorId ||
            settlement.paidAt != receipt.committedAt || settlement.paymentMethod == PaymentMethod.UNKNOWN || settlement.amount.compareTo(expense.amount) != 0 ||
            paid.single().posting.method != settlement.paymentMethod || paid.single().posting.amount.compareTo(expense.amount.negate()) != 0)) return unavailable
        if (accrual.any { it.posting.amount.compareTo(expense.amount) != 0 || it.posting.method != (settlement?.paymentMethod ?: PaymentMethod.OTHER) }) return unavailable
        return ExpenseCommandProjectionResult.Found(ExpenseCommandProjection(receipt.commandId, receipt.kind, operation, receipt.cashSessionId,
            expense.id, receipt.settlementId, receipt.ledgerEventIds, expense.copy(paymentMethod = settlement?.paymentMethod), settlement,
            ExpenseProjectionEvidence(receipt.committedAt, postings, snapshot)))
    }
}
