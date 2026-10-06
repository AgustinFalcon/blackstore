package com.blackstore.application.sales

import com.blackstore.domain.sales.*

data class DurableSaleView(val sale: StoredSale, val cashierId: Long, val snapshot: PaymentSnapshot,
    val payments: List<PaymentLedgerEntry>, val allowedActions: Set<SaleAllowedAction>) {
    val createdBy: Long? get() = sale.saga.createdBy?.takeIf { it > 0 }
}
data class DurableSalePage(val items: List<DurableSaleView>, val nextCursor: Long?)
