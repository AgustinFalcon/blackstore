package com.blackstore.domain.accounting

import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.sales.MoneyPolicy
import com.blackstore.domain.sales.PaymentMethod
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class AccountingCommandKind {
    CASH_SESSION_OPEN, CASH_SESSION_CLOSE, EXPENSE_RECORD, PAYMENT_CAPTURE, PAYMENT_REVERSE, FEE_RECORD, ADJUSTMENT_RECORD, COMMERCIAL_RECOGNITION, Unknown;
    companion object { fun fromWire(value: String?): AccountingCommandKind = entries.firstOrNull { it.name == value } ?: Unknown }
}

enum class AccountingCommandFailure(val wire: String) {
    NotVisible("NOT_VISIBLE"), Forbidden("FORBIDDEN"), Validation("VALIDATION"), Closed("CLOSED"),
    NonTerminalSale("NON_TERMINAL_SALE"), PayloadMismatch("PAYLOAD_MISMATCH"),
    LegacyContractDisabled("LEGACY_CONTRACT_DISABLED"), NotActivated("NOT_ACTIVATED"),
    Paused("PAUSED"), Unavailable("UNAVAILABLE"), Unknown("UNKNOWN");
    companion object { fun fromWire(value: String?): AccountingCommandFailure = entries.firstOrNull { it.wire == value } ?: Unknown }
}

data class AccountingCommandReceipt(
    val commandId: UUID,
    val actorId: Long,
    val kind: AccountingCommandKind,
    val cashSessionId: Long,
    val payloadHash: String,
    val ledgerEventIds: List<Long>,
    val committedAt: Instant,
) {
    init {
        require(actorId > 0 && cashSessionId > 0 && kind != AccountingCommandKind.Unknown)
        require(payloadHash.length == 64 && payloadHash.all { it in '0'..'9' || it in 'a'..'f' })
        require(ledgerEventIds.all { it > 0 } && ledgerEventIds.distinct().size == ledgerEventIds.size)
    }
}

sealed class AccountingCommandResult {
    data class Committed(val receipt: AccountingCommandReceipt) : AccountingCommandResult()
    data object NotFound : AccountingCommandResult()
    data object Unavailable : AccountingCommandResult()
    data object Unknown : AccountingCommandResult()
}

/** Closed v2 command vocabulary. HTTP DTOs translate into these framework-free values once. */
sealed interface AccountingCommandDraft {
    val commandId: UUID

    data class CashSessionOpen(
        override val commandId: UUID,
        val terminalId: Long,
        val cashierId: Long,
        val openingCash: BigDecimal,
        val reason: String?,
    ) : AccountingCommandDraft {
        init {
            require(terminalId > 0 && cashierId > 0 && openingCash.signum() >= 0)
            MoneyPolicy.normalize(openingCash)
        }
    }

    data class CashSessionClose(
        override val commandId: UUID,
        val cashSessionId: Long,
        val declaredCash: BigDecimal,
        val reason: String,
    ) : AccountingCommandDraft {
        init {
            require(cashSessionId > 0 && declaredCash.signum() >= 0 && reason.isNotBlank())
            MoneyPolicy.normalize(declaredCash)
        }
    }

    data class ExpenseRecord(
        override val commandId: UUID,
        val cashSessionId: Long,
        val category: String,
        val amount: BigDecimal,
        val reason: String,
        val method: PaymentMethod,
    ) : AccountingCommandDraft {
        init {
            require(cashSessionId > 0 && category.isNotBlank() && amount.signum() > 0 && reason.isNotBlank())
            require(method != PaymentMethod.UNKNOWN)
            MoneyPolicy.normalize(amount)
        }
    }

    data class PaymentCapture(
        override val commandId: UUID,
        val identity: OperationQuadruple,
        val method: PaymentMethod,
        val amount: BigDecimal,
    ) : AccountingCommandDraft {
        init {
            require(method != PaymentMethod.UNKNOWN && amount.signum() > 0)
            MoneyPolicy.normalize(amount)
        }
    }

    data class PaymentReverse(
        override val commandId: UUID,
        val identity: OperationQuadruple,
        val originalPaymentId: Long,
        val reason: String,
        val evidenceRef: String,
    ) : AccountingCommandDraft {
        init { require(originalPaymentId > 0 && reason.isNotBlank() && evidenceRef.isNotBlank()) }
    }
}
