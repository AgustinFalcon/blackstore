package com.blackstore.domain.ledger

import com.blackstore.domain.exception.ForbiddenOperationException
import com.blackstore.domain.sales.PaymentMethod
import java.math.BigDecimal
import java.time.Instant

enum class CashEventType {
    OPENING,
    PAYMENT,
    REFUND,
    FEE,
    EXPENSE_ACCRUAL,
    EXPENSE_PAID,
    CLOSING_ADJUSTMENT,
    REVERSAL,
    ADJUSTMENT,
}

data class CashLedgerEvent(
    val id: Long,
    val cashSessionId: Long,
    val eventType: CashEventType,
    val amountDelta: BigDecimal,
    val actorId: Long,
    val reason: String? = null,
    val evidenceRef: String? = null,
    val originalEventId: Long? = null,
) {
    init {
        if (amountDelta.signum() == 0 && eventType != CashEventType.OPENING) {
            throw IllegalArgumentException("zero cash event is allowed only for OPENING")
        }
        if (eventType == CashEventType.REVERSAL || eventType == CashEventType.ADJUSTMENT || eventType == CashEventType.CLOSING_ADJUSTMENT) {
            require(originalEventId != null && !reason.isNullOrBlank() && !evidenceRef.isNullOrBlank())
        }
    }
}

data class ExpenseRecord(
    val id: Long,
    val cashSessionId: Long,
    val category: String,
    val amount: BigDecimal,
    val reason: String,
    val paymentMethod: PaymentMethod,
    val actorId: Long,
    val accruedAt: Instant,
) {
    init {
        require(amount.signum() > 0 && reason.isNotBlank() && category.isNotBlank())
    }
}

data class InboxKey(
    val clientInstanceId: String,
    val deviceId: String,
    val saleId: String,
    val operationId: String,
    val operationKind: String,
    val responseHash: String,
)

class AppendOnlyLedger {

    fun append(
        existing: List<CashLedgerEvent>,
        event: CashLedgerEvent,
    ): List<CashLedgerEvent> {
        if (event.eventType == CashEventType.OPENING && existing.any { it.cashSessionId == event.cashSessionId && it.eventType == CashEventType.OPENING }) {
            throw ForbiddenOperationException("one OPENING event per cash session")
        }
        return existing + event
    }

    fun rejectMutation() {
        throw ForbiddenOperationException("historical cash, expense, inbox and audit rows are append-only")
    }
}

class InboxDeduplicator {

    fun accept(
        existing: Set<InboxKey>,
        incoming: InboxKey,
    ): Set<InboxKey> = existing + incoming

    fun isDuplicate(
        existing: Set<InboxKey>,
        incoming: InboxKey,
    ): Boolean = incoming in existing
}
