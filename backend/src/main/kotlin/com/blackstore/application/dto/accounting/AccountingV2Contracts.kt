package com.blackstore.application.dto.accounting

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import com.blackstore.domain.accounting.AccountingCommandDraft
import com.blackstore.domain.accounting.AccountingCommandFailure
import com.blackstore.domain.accounting.AccountingCommandReceipt
import com.blackstore.domain.accounting.AccountingCommandResult
import com.blackstore.domain.accounting.ExpenseInstruction
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.sales.PaymentMethod
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class CashSessionOpenV2Request(val commandId: UUID, val terminalId: Long, val cashierId: Long, val openingCash: BigDecimal, val reason: String? = null)
data class CashSessionCloseV2Request(val commandId: UUID, val cashSessionId: Long, val declaredCash: BigDecimal, val reason: String)
data class ExpenseRecordV2Request(val commandId: UUID, val cashSessionId: Long, val operation: String?, val category: String? = null, val amount: BigDecimal? = null, val expenseId: Long? = null, val reason: String, val paymentMethod: String? = null)
data class PaymentCaptureV2Request(val commandId: UUID, val clientInstanceId: String, val deviceId: String, val saleId: String, val operationId: String, val paymentMethod: String?, val amount: BigDecimal, val reason: String? = null)
data class PaymentReverseV2Request(val commandId: UUID, val clientInstanceId: String, val deviceId: String, val saleId: String, val operationId: String, val originalPaymentId: Long, val reason: String, val evidenceRef: String)

/** The only v2 request-edge translator. Unknown wire values fail before domain/application use. */
object AccountingV2RequestTranslator {
    fun translate(request: CashSessionOpenV2Request): AccountingCommandDraft = AccountingCommandDraft.CashSessionOpen(request.commandId, request.terminalId, request.cashierId, request.openingCash, request.reason)
    fun translate(request: CashSessionCloseV2Request): AccountingCommandDraft = AccountingCommandDraft.CashSessionClose(request.commandId, request.cashSessionId, request.declaredCash, request.reason)
    fun translate(request: ExpenseRecordV2Request): AccountingCommandDraft = AccountingCommandDraft.ExpenseRecord(
        request.commandId,
        request.cashSessionId,
        request.reason,
        when (ExpenseOperationV2.fromWire(request.operation)) {
            ExpenseOperationV2.Accrue -> ExpenseInstruction.Accrue(requireNotNull(request.category), requireNotNull(request.amount)).also { require(request.expenseId == null && request.paymentMethod == null) }
            ExpenseOperationV2.AccrueAndSettle -> ExpenseInstruction.AccrueAndSettle(requireNotNull(request.category), requireNotNull(request.amount), knownMethod(request.paymentMethod)).also { require(request.expenseId == null) }
            ExpenseOperationV2.SettleExisting -> ExpenseInstruction.SettleExisting(requireNotNull(request.expenseId), knownMethod(request.paymentMethod)).also { require(request.category == null && request.amount == null) }
            ExpenseOperationV2.Unknown -> throw IllegalArgumentException("unknown expense operation")
        },
    )
    fun translate(request: PaymentCaptureV2Request): AccountingCommandDraft = AccountingCommandDraft.PaymentCapture(request.commandId, identity(request.clientInstanceId, request.deviceId, request.saleId, request.operationId), knownMethod(request.paymentMethod), request.amount, request.reason)
    fun translate(request: PaymentReverseV2Request): AccountingCommandDraft = AccountingCommandDraft.PaymentReverse(request.commandId, identity(request.clientInstanceId, request.deviceId, request.saleId, request.operationId), request.originalPaymentId, request.reason, request.evidenceRef)

    private fun knownMethod(wire: String?): PaymentMethod = PaymentMethod.fromWire(wire).also { require(it != PaymentMethod.UNKNOWN) { "unknown payment method" } }
    private fun identity(clientInstanceId: String, deviceId: String, saleId: String, operationId: String) = OperationQuadruple(clientInstanceId, deviceId, saleId, operationId)
}

enum class ExpenseOperationV2(val wire: String) {
    Accrue("ACCRUE"), AccrueAndSettle("ACCRUE_AND_SETTLE"), SettleExisting("SETTLE_EXISTING"), Unknown("UNKNOWN");
    companion object { fun fromWire(value: String?): ExpenseOperationV2 = entries.firstOrNull { it.wire == value } ?: Unknown }
}

enum class AccountingCommandOutcomeV2(@get:JsonValue val wire: String, val status: Int) {
    Committed("COMMITTED", 200), NotFound("NOT_FOUND", 404), Unavailable("UNAVAILABLE", 503), Unknown("UNKNOWN", 503);
    companion object { @JvmStatic @JsonCreator fun fromWire(value: String?): AccountingCommandOutcomeV2 = entries.firstOrNull { it.wire == value } ?: Unknown }
}

enum class AccountingCommandFailureV2(@get:JsonValue val wire: String, val status: Int) {
    NotVisible("NOT_VISIBLE", 404), Forbidden("FORBIDDEN", 403), Validation("VALIDATION", 400), Closed("CLOSED", 409),
    TransitionConflict("PAYMENT_TRANSITION_CONFLICT", 409), CashSessionConflict("CASH_SESSION_CONFLICT", 409),
    NonTerminalSale("NON_TERMINAL_SALE", 409), PayloadMismatch("PAYLOAD_MISMATCH", 409),
    LegacyContractDisabled("LEGACY_CONTRACT_DISABLED", 409), NotActivated("NOT_ACTIVATED", 409),
    Paused("PAUSED", 409), Unavailable("UNAVAILABLE", 503), Unknown("UNKNOWN", 503);
    companion object { @JvmStatic @JsonCreator fun fromWire(value: String?): AccountingCommandFailureV2 = entries.firstOrNull { it.wire == value } ?: Unknown }
}

data class AccountingCommandReceiptV2Response(
    val outcome: AccountingCommandOutcomeV2,
    val commandId: UUID?,
    val ledgerEventIds: List<Long>,
    val committedAt: Instant?,
    val failure: AccountingCommandFailureV2?,
    val cashSessionId: Long? = null,
    val paymentId: Long? = null,
    val expenseId: Long? = null,
    val settlementId: Long? = null,
    val closeSnapshot: CashCloseSnapshotV2Response? = null,
)

enum class ReconciliationOutcomeV2(@get:JsonValue val wire: String) {
    Balanced("BALANCED"), Shortage("SHORTAGE"), Overage("OVERAGE"), Unavailable("UNAVAILABLE"), Unknown("UNKNOWN");
    companion object { @JvmStatic @JsonCreator fun fromWire(value: String?): ReconciliationOutcomeV2 = entries.firstOrNull { it.wire == value } ?: Unknown }
}

enum class AccountingCoverageV2(@get:JsonValue val wire: String) {
    CompleteFromOpening("COMPLETE_FROM_OPENING"), LegacyIncomplete("LEGACY_INCOMPLETE"), Unknown("UNKNOWN");
    companion object { @JvmStatic @JsonCreator fun fromWire(value: String?): AccountingCoverageV2 = entries.firstOrNull { it.wire == value } ?: Unknown }
}

data class CashCloseSnapshotV2Response(val declaredCash: BigDecimal, val expectedCash: BigDecimal?, val difference: BigDecimal?,
    val outcome: ReconciliationOutcomeV2, val coverage: AccountingCoverageV2, val cutoff: Instant, val localWatermark: Long, val accountingVersion: Int)

object AccountingV2ResponseTranslator {
    fun rejected(failure: AccountingCommandFailure): AccountingCommandReceiptV2Response = AccountingCommandReceiptV2Response(
        when (failure) {
            AccountingCommandFailure.NotVisible -> AccountingCommandOutcomeV2.NotFound
            AccountingCommandFailure.Unavailable -> AccountingCommandOutcomeV2.Unavailable
            else -> AccountingCommandOutcomeV2.Unknown
        }, null, emptyList(), null, AccountingCommandFailureV2.fromWire(failure.wire))
    fun translate(result: AccountingCommandResult): AccountingCommandReceiptV2Response = when (result) {
        is AccountingCommandResult.Committed -> result.receipt.toResponse()
        AccountingCommandResult.NotFound -> AccountingCommandReceiptV2Response(AccountingCommandOutcomeV2.NotFound, null, emptyList(), null, null)
        AccountingCommandResult.Unavailable -> AccountingCommandReceiptV2Response(AccountingCommandOutcomeV2.Unavailable, null, emptyList(), null, AccountingCommandFailureV2.fromWire(AccountingCommandFailure.Unavailable.wire))
        AccountingCommandResult.Unknown -> AccountingCommandReceiptV2Response(AccountingCommandOutcomeV2.Unknown, null, emptyList(), null, AccountingCommandFailureV2.fromWire(AccountingCommandFailure.Unknown.wire))
    }

    private fun AccountingCommandReceipt.toResponse() = AccountingCommandReceiptV2Response(AccountingCommandOutcomeV2.Committed, commandId, ledgerEventIds, committedAt, null,
        cashSessionId, paymentId, expenseId, settlementId, closeSnapshot?.let {
            CashCloseSnapshotV2Response(it.declaredCash, it.expectedCash, it.difference, ReconciliationOutcomeV2.fromWire(it.outcome.wire),
                AccountingCoverageV2.fromWire(it.coverage.wire), it.cutoff, it.localWatermark, it.accountingVersion)
        })
}
