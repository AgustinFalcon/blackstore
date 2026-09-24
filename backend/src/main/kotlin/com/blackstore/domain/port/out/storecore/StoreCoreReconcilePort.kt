package com.blackstore.domain.port.out.storecore

import com.blackstore.domain.model.StoreCoreOperationReceipt

/**
 * Read-only reconcile against caller-supplied receipts.
 * Unknown receipts never authorize a replacement POST.
 */
interface StoreCoreReconcilePort {
    fun reconcile(query: ReconcileQuery): ReconcileProjection
}

data class ReconcileQuery(
    val knownReceipts: List<String>,
    val clientInstanceId: String = "",
) {
    init {
        require(knownReceipts.isNotEmpty()) { "knownReceipts must not be empty" }
        require(knownReceipts.all { it.isNotBlank() }) { "knownReceipts must not contain blanks" }
        require(knownReceipts.size <= 500) { "knownReceipts exceeds reconcile bound" }
    }
}

data class ReconcileProjection(
    val present: List<StoreCoreOperationReceipt>,
    val unknownReceipts: List<String>,
) {
    fun unknownAuthorizesRepost(): Boolean = false
}
