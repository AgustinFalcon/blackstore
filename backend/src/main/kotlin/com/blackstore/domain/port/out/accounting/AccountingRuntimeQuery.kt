package com.blackstore.domain.port.out.accounting

import com.blackstore.domain.sales.PaymentLedgerSemantics

/** Runtime source for selecting the accounting interpretation; callers never infer it from ledger rows. */
fun interface AccountingRuntimeQuery {
    fun paymentLedgerSemantics(): PaymentLedgerSemantics
}
