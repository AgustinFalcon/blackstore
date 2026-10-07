package com.blackstore.domain.port.out.counter

import com.blackstore.domain.reports.ShiftFigures
import com.blackstore.domain.sales.PaymentRecord
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.sales.OperationLedger

interface CounterEntryStore {
    fun paymentLedger(identity: OperationQuadruple): OperationLedger
    fun savePayment(payment: PaymentRecord, identity: OperationQuadruple): PaymentRecord
    fun savePayment(payment: PaymentRecord, operationId: String): PaymentRecord

    fun findPayment(id: Long): PaymentRecord?

    fun figures(): ShiftFigures
}
