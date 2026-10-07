package com.blackstore.application.dto.accounting

import com.blackstore.domain.accounting.AccountingCommandDraft
import com.blackstore.domain.accounting.AccountingCommandFailure
import com.blackstore.domain.accounting.AccountingCommandReceipt
import com.blackstore.domain.accounting.AccountingCommandResult
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.sales.PaymentMethod
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class CashSessionOpenV2Request(val commandId: UUID, val terminalId: Long, val cashierId: Long, val openingCash: BigDecimal, val reason: String? = null)
data class CashSessionCloseV2Request(val commandId: UUID, val cashSessionId: Long, val declaredCash: BigDecimal, val reason: String)
data class ExpenseRecordV2Request(val commandId: UUID, val cashSessionId: Long, val category: String, val amount: BigDecimal, val reason: String, val paymentMethod: String?)
data class PaymentCaptureV2Request(val commandId: UUID, val clientInstanceId: String, val deviceId: String, val saleId: String, val operationId: String, val paymentMethod: String?, val amount: BigDecimal)
data class PaymentReverseV2Request(val commandId: UUID, val clientInstanceId: String, val deviceId: String, val saleId: String, val operationId: String, val originalPaymentId: Long, val reason: String, val evidenceRef: String)

/** The only v2 request-edge translator. Unknown wire values fail before domain/application use. */
object AccountingV2RequestTranslator {
    fun translate(request: CashSessionOpenV2Request): AccountingCommandDraft = AccountingCommandDraft.CashSessionOpen(request.commandId, request.terminalId, request.cashierId, request.openingCash, request.reason)
    fun translate(request: CashSessionCloseV2Request): AccountingCommandDraft = AccountingCommandDraft.CashSessionClose(request.commandId, request.cashSessionId, request.declaredCash, request.reason)
    fun translate(request: ExpenseRecordV2Request): AccountingCommandDraft = AccountingCommandDraft.ExpenseRecord(request.commandId, request.cashSessionId, request.category, request.amount, request.reason, knownMethod(request.paymentMethod))
    fun translate(request: PaymentCaptureV2Request): AccountingCommandDraft = AccountingCommandDraft.PaymentCapture(request.commandId, identity(request.clientInstanceId, request.deviceId, request.saleId, request.operationId), knownMethod(request.paymentMethod), request.amount)
    fun translate(request: PaymentReverseV2Request): AccountingCommandDraft = AccountingCommandDraft.PaymentReverse(request.commandId, identity(request.clientInstanceId, request.deviceId, request.saleId, request.operationId), request.originalPaymentId, request.reason, request.evidenceRef)

    private fun knownMethod(wire: String?): PaymentMethod = PaymentMethod.fromWire(wire).also { require(it != PaymentMethod.UNKNOWN) { "unknown payment method" } }
    private fun identity(clientInstanceId: String, deviceId: String, saleId: String, operationId: String) = OperationQuadruple(clientInstanceId, deviceId, saleId, operationId)
}

enum class AccountingCommandOutcomeV2(val wire: String) {
    Committed("COMMITTED"), NotFound("NOT_FOUND"), Unavailable("UNAVAILABLE"), Unknown("UNKNOWN");
    companion object { fun fromWire(value: String?): AccountingCommandOutcomeV2 = entries.firstOrNull { it.wire == value } ?: Unknown }
}

data class AccountingCommandReceiptV2Response(
    val outcome: AccountingCommandOutcomeV2,
    val commandId: UUID?,
    val ledgerEventIds: List<Long>,
    val committedAt: Instant?,
    val failure: AccountingCommandFailure?,
)

object AccountingV2ResponseTranslator {
    fun translate(result: AccountingCommandResult): AccountingCommandReceiptV2Response = when (result) {
        is AccountingCommandResult.Committed -> result.receipt.toResponse()
        AccountingCommandResult.NotFound -> AccountingCommandReceiptV2Response(AccountingCommandOutcomeV2.NotFound, null, emptyList(), null, null)
        AccountingCommandResult.Unavailable -> AccountingCommandReceiptV2Response(AccountingCommandOutcomeV2.Unavailable, null, emptyList(), null, AccountingCommandFailure.Unavailable)
        AccountingCommandResult.Unknown -> AccountingCommandReceiptV2Response(AccountingCommandOutcomeV2.Unknown, null, emptyList(), null, AccountingCommandFailure.Unknown)
    }

    private fun AccountingCommandReceipt.toResponse() = AccountingCommandReceiptV2Response(AccountingCommandOutcomeV2.Committed, commandId, ledgerEventIds, committedAt, null)
}
