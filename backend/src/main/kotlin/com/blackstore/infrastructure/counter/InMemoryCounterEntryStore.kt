package com.blackstore.infrastructure.counter

import com.blackstore.domain.ledger.ExpenseRecord
import com.blackstore.domain.port.out.counter.CounterEntryStore
import com.blackstore.domain.reports.ShiftFigures
import com.blackstore.domain.sales.PaymentRecord
import com.blackstore.domain.sales.PaymentStatus
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

    override fun savePayment(payment: PaymentRecord, operationId: String): PaymentRecord {
        payments += payment
        return payment
    }

    override fun findPayment(id: Long): PaymentRecord? = payments.firstOrNull { it.id == id }

    override fun saveExpense(expense: ExpenseRecord) {
        expenses += expense
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
