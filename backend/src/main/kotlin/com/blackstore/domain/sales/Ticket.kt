package com.blackstore.domain.sales

import java.math.BigDecimal

data class TicketLine(
    val sku: String,
    val productName: String,
    val quantity: Int,
    val originalUnitPrice: BigDecimal,
    val discountAmount: BigDecimal,
) {
    val effectiveUnitPrice: BigDecimal = originalUnitPrice.subtract(discountAmount)

    init {
        require(sku.isNotBlank() && productName.isNotBlank())
        require(quantity > 0)
        require(originalUnitPrice.signum() >= 0)
        require(discountAmount.signum() >= 0 && discountAmount <= originalUnitPrice)
    }
}

enum class PaymentMethod {
    CASH,
    CARD,
    TRANSFER,
    OTHER,
}

enum class PaymentStatus {
    PENDING,
    CAPTURED,
    VOIDED,
    REFUNDED,
}

data class PaymentRecord(
    val id: Long,
    val method: PaymentMethod,
    val amount: BigDecimal,
    val feeAmount: BigDecimal,
    val status: PaymentStatus,
    val originalPaymentId: Long? = null,
    val actorId: Long? = null,
    val reason: String? = null,
    val evidenceRef: String? = null,
) {
    init {
        require(amount.signum() > 0)
        require(feeAmount.signum() >= 0)
        if (status == PaymentStatus.VOIDED || status == PaymentStatus.REFUNDED) {
            require(originalPaymentId != null && !reason.isNullOrBlank() && !evidenceRef.isNullOrBlank())
        }
    }
}

class PaymentBook {

    fun capture(
        id: Long,
        method: PaymentMethod,
        amount: BigDecimal,
        feeAmount: BigDecimal,
    ): PaymentRecord =
        PaymentRecord(
            id = id,
            method = method,
            amount = amount,
            feeAmount = feeAmount,
            status = PaymentStatus.CAPTURED,
        )

    fun reverse(
        original: PaymentRecord,
        reversalId: Long,
        actorId: Long,
        reason: String,
        evidenceRef: String,
    ): PaymentRecord {
        require(original.status == PaymentStatus.CAPTURED) { "only a captured payment can be reversed" }
        return PaymentRecord(
            id = reversalId,
            method = original.method,
            amount = original.amount,
            feeAmount = original.feeAmount,
            status = PaymentStatus.REFUNDED,
            originalPaymentId = original.id,
            actorId = actorId,
            reason = reason,
            evidenceRef = evidenceRef,
        )
    }
}
