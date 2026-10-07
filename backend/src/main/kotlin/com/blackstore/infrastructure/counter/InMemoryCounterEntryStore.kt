package com.blackstore.infrastructure.counter

import com.blackstore.domain.ledger.ExpenseRecord
import com.blackstore.domain.port.out.counter.CounterEntryStore
import com.blackstore.domain.reports.ShiftFigures
import com.blackstore.domain.sales.PaymentRecord
import com.blackstore.domain.sales.PaymentStatus
import com.blackstore.domain.sales.OperationLedger
import com.blackstore.domain.sales.PaymentLedgerEntry
import com.blackstore.domain.model.OperationQuadruple
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.math.BigDecimal

@Component
@ConditionalOnProperty(
    name = ["blackstore.persistence.enabled"],
    havingValue = "false",
    matchIfMissing = true,
)
class InMemoryCounterEntryStore : CounterEntryStore {
    private val payments = mutableListOf<PaymentRecord>()
    private val expenses = mutableListOf<ExpenseRecord>()
    private val identities = mutableMapOf<Long, OperationQuadruple>()
    private val operationIds = mutableMapOf<Long, String>()

    override fun paymentLedger(identity: OperationQuadruple): OperationLedger = synchronized(this) {
        if (payments.any { operationIds[it.id] == identity.operationId && identities[it.id] == null }) return@synchronized OperationLedger.Unknown
        OperationLedger.Known(payments.filter { identities[it.id] == identity }.map {
            PaymentLedgerEntry(identity, it.id, it.method, it.status, it.amount, it.feeAmount, it.originalPaymentId)
        })
    }

    override fun savePayment(payment: PaymentRecord, identity: OperationQuadruple): PaymentRecord = synchronized(this) {
        require(payments.none { it.id == payment.id })
        if (payment.originalPaymentId != null) require(identities[payment.originalPaymentId] == identity)
        identities[payment.id] = identity
        savePayment(payment, identity.operationId)
    }

    override fun savePayment(payment: PaymentRecord, operationId: String): PaymentRecord {
        payments += payment
        operationIds[payment.id] = operationId
        return payment
    }

    override fun findPayment(id: Long): PaymentRecord? = payments.firstOrNull { it.id == id }

    internal fun saveExpense(expense: ExpenseRecord) = synchronized(this) {
        expenses.add(expense)
        Unit
    }
    internal fun <T> atomicExpenses(action: () -> T): T = synchronized(this) {
        val previous=expenses.toList()
        try { action() } catch(error: Exception) { expenses.clear();expenses.addAll(previous);throw error }
    }

    override fun figures(): ShiftFigures {
        val captured = payments.filter { it.status == PaymentStatus.CAPTURED }
        val refunded = payments.filter { it.status == PaymentStatus.REFUNDED }
        val collected = captured.fold(BigDecimal.ZERO) { acc, payment -> acc.add(payment.amount) }
        val fees = captured.fold(BigDecimal.ZERO) { acc, payment -> acc.add(payment.feeAmount) }
        val refunds = refunded.fold(BigDecimal.ZERO) { acc, payment -> acc.add(payment.amount) }
        val expenseTotal = expenses.fold(BigDecimal.ZERO) { acc, expense -> acc.add(expense.amount) }
        return ShiftFigures(
            grossSales = collected.add(refunds),
            discounts = BigDecimal.ZERO,
            refunds = refunds,
            collected = collected,
            feesPaid = fees,
            expensesPaid = expenseTotal,
        )
    }
}
