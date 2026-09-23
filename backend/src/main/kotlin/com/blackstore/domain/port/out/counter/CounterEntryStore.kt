package com.blackstore.domain.port.out.counter

import com.blackstore.domain.reports.ShiftFigures
import com.blackstore.domain.sales.PaymentRecord
import com.blackstore.domain.ledger.ExpenseRecord

interface CounterEntryStore {
    fun savePayment(payment: PaymentRecord, operationId: String): PaymentRecord

    fun findPayment(id: Long): PaymentRecord?

    fun saveExpense(expense: ExpenseRecord)

    fun figures(): ShiftFigures
}
