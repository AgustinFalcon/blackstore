package com.blackstore.domain.accounting

import com.blackstore.domain.sales.MoneyPolicy
import com.blackstore.domain.sales.PaymentMethod
import com.blackstore.domain.sales.PaymentRecord
import com.blackstore.domain.sales.PaymentStatus
import java.math.BigDecimal
import java.util.UUID

enum class LedgerEventKind { OPENING, PAYMENT, REFUND, FEE, EXPENSE_ACCRUAL, EXPENSE_PAID, ADJUSTMENT, Unknown;
    companion object { fun fromWire(value: String?): LedgerEventKind = entries.firstOrNull { it.name == value } ?: Unknown }
}
enum class LedgerComponent { OPENING, PAYMENT_CAPTURE, PAYMENT_REFUND, FEE_PAID, EXPENSE_ACCRUAL, EXPENSE_SETTLEMENT, ADJUSTMENT, Unknown;
    companion object { fun fromWire(value: String?): LedgerComponent = entries.firstOrNull { it.name == value } ?: Unknown }
}
enum class LedgerOriginKind { CASH_SESSION, PAYMENT, EXPENSE, OPERATION, Unknown;
    companion object { fun fromWire(value: String?): LedgerOriginKind = entries.firstOrNull { it.name == value } ?: Unknown }
}

data class LedgerOrigin(val kind: LedgerOriginKind, val id: String) {
    constructor(kind: LedgerOriginKind, id: Long) : this(kind, id.toString())
    constructor(kind: LedgerOriginKind, id: UUID) : this(kind, id.toString())
    init {
        require(kind != LedgerOriginKind.Unknown)
        if (kind == LedgerOriginKind.OPERATION) require(UUID.fromString(id).toString() == id)
        else require(id.toLongOrNull()?.let { it > 0 && it.toString() == id } == true)
    }
}

data class AccountingEvidence(val originalEventId: Long?, val reason: String, val reference: String) {
    init { require((originalEventId == null || originalEventId > 0) && reason.isNotBlank() && reference.isNotBlank()) }
}

/** Amount is signed financial movement; accrual is a positive liability fact, never paid cash. */
data class LedgerPosting(
    val kind: LedgerEventKind,
    val component: LedgerComponent,
    val method: PaymentMethod,
    val amount: BigDecimal,
    val origin: LedgerOrigin,
    val evidence: AccountingEvidence? = null,
) {
    init {
        MoneyPolicy.normalize(amount)
        require(method != PaymentMethod.UNKNOWN)
        require(when (kind) {
            LedgerEventKind.OPENING -> component == LedgerComponent.OPENING && method == PaymentMethod.CASH && amount.signum() >= 0 && origin.kind == LedgerOriginKind.CASH_SESSION
            LedgerEventKind.PAYMENT -> component == LedgerComponent.PAYMENT_CAPTURE && amount.signum() > 0 && origin.kind == LedgerOriginKind.PAYMENT
            LedgerEventKind.REFUND -> component == LedgerComponent.PAYMENT_REFUND && amount.signum() < 0 && origin.kind == LedgerOriginKind.PAYMENT && evidence?.originalEventId != null
            LedgerEventKind.FEE -> component == LedgerComponent.FEE_PAID && amount.signum() < 0 && origin.kind == LedgerOriginKind.OPERATION && evidence != null
            LedgerEventKind.EXPENSE_ACCRUAL -> component == LedgerComponent.EXPENSE_ACCRUAL && amount.signum() > 0 && origin.kind == LedgerOriginKind.EXPENSE
            LedgerEventKind.EXPENSE_PAID -> component == LedgerComponent.EXPENSE_SETTLEMENT && amount.signum() < 0 && origin.kind == LedgerOriginKind.EXPENSE
            LedgerEventKind.ADJUSTMENT -> component == LedgerComponent.ADJUSTMENT && amount.signum() != 0 && origin.kind == LedgerOriginKind.OPERATION && evidence?.originalEventId != null
            LedgerEventKind.Unknown -> false
        }) { "invalid ledger component, origin, sign or evidence" }
    }

    val cashDelta: BigDecimal get() = if (method == PaymentMethod.CASH && kind != LedgerEventKind.EXPENSE_ACCRUAL) MoneyPolicy.normalize(amount) else MoneyPolicy.normalize(BigDecimal.ZERO)
    val operatingFlowDelta: BigDecimal get() = when (kind) {
        LedgerEventKind.PAYMENT, LedgerEventKind.REFUND, LedgerEventKind.FEE, LedgerEventKind.EXPENSE_PAID -> MoneyPolicy.normalize(amount)
        else -> MoneyPolicy.normalize(BigDecimal.ZERO)
    }
}

class CashPostingPolicy {
    fun opening(sessionId: Long, amount: BigDecimal): LedgerPosting = LedgerPosting(LedgerEventKind.OPENING, LedgerComponent.OPENING, PaymentMethod.CASH, MoneyPolicy.normalize(amount), LedgerOrigin(LedgerOriginKind.CASH_SESSION, sessionId))

    fun expectedCash(postings: List<LedgerPosting>): BigDecimal {
        require(postings.count { it.kind == LedgerEventKind.OPENING } == 1) { "complete cash requires exactly one opening" }
        return MoneyPolicy.normalize(postings.fold(BigDecimal.ZERO) { sum, posting -> sum + posting.cashDelta })
    }
}

class PaymentPostingPolicy {
    /** Provider fee metadata on a capture is deliberately not a paid fee fact. */
    fun capture(payment: PaymentRecord): LedgerPosting {
        require(payment.status == PaymentStatus.CAPTURED && payment.originalPaymentId == null)
        return LedgerPosting(LedgerEventKind.PAYMENT, LedgerComponent.PAYMENT_CAPTURE, payment.method, MoneyPolicy.normalize(payment.amount), LedgerOrigin(LedgerOriginKind.PAYMENT, payment.id))
    }

    fun refund(original: PaymentRecord, refund: PaymentRecord, originalEventId: Long): LedgerPosting {
        require(original.status == PaymentStatus.CAPTURED && refund.status == PaymentStatus.REFUNDED)
        require(refund.originalPaymentId == original.id && refund.method == original.method && refund.amount.compareTo(original.amount) == 0)
        return LedgerPosting(LedgerEventKind.REFUND, LedgerComponent.PAYMENT_REFUND, refund.method, MoneyPolicy.normalize(refund.amount.negate()), LedgerOrigin(LedgerOriginKind.PAYMENT, refund.id), AccountingEvidence(originalEventId, requireNotNull(refund.reason), requireNotNull(refund.evidenceRef)))
    }

    fun paidFee(operationId: UUID, method: PaymentMethod, amount: BigDecimal, evidence: AccountingEvidence): LedgerPosting {
        require(amount.signum() > 0)
        return LedgerPosting(LedgerEventKind.FEE, LedgerComponent.FEE_PAID, method, MoneyPolicy.normalize(amount.negate()), LedgerOrigin(LedgerOriginKind.OPERATION, operationId), evidence)
    }
}
