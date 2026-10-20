package com.blackstore.application.dto.sales

import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.sales.*
import com.fasterxml.jackson.annotation.JsonAnySetter
import java.math.BigDecimal
import java.util.UUID

data class SaleReserveV2Request(val commandId: UUID,val clientInstanceId: String,val deviceId: String,val saleId: String,
    val operationId: String,val cashSessionId: Long,val variantId: String,val quantity: Int,val expectedPriceVersion: String,
    val sku: String,val productName: String,val originalUnitPrice: BigDecimal,val discountAmount: BigDecimal=BigDecimal.ZERO,val reason: String?=null) {
    @JsonAnySetter fun rejectUnknown(name: String,value: Any?) { throw IllegalArgumentException("unsupported sale field") }
}
data class SaleTerminalV2Request(val commandId: UUID,val clientInstanceId: String,val deviceId: String,val saleId: String,
    val operationId: String,val reason: String?=null) {
    @JsonAnySetter fun rejectUnknown(name: String,value: Any?) { throw IllegalArgumentException("unsupported sale field") }
}
object SaleV2RequestTranslator {
    private fun id(raw: String): String { val parsed=UUID.fromString(raw);require(parsed.toString()==raw);return raw }
    private fun ref(raw: String): String { require(raw.isNotBlank() && raw.length<=80 && raw==raw.trim());return raw }
    private fun identity(client: String,device: String,sale: String,operation: String)=OperationQuadruple(id(client),ref(device),ref(sale),id(operation))
    private fun reason(raw: String?): String? = raw?.trim()?.also { require(it.isNotEmpty() && it.length<=500) }
    fun reserve(r: SaleReserveV2Request): SaleCommand.Reserve=SaleCommand.Reserve(r.commandId,identity(r.clientInstanceId,r.deviceId,r.saleId,r.operationId),r.cashSessionId,ref(r.variantId),r.quantity,ref(r.expectedPriceVersion),
        TicketLine(ref(r.sku),r.productName.trim().also { require(it.isNotEmpty() && it.length<=250) },r.quantity,MoneyPolicy.normalize(r.originalUnitPrice),MoneyPolicy.normalize(r.discountAmount)),reason(r.reason))
    fun terminal(path: String,r: SaleTerminalV2Request,kind: SaleCommandKind): SaleCommand {
        require(path==r.operationId)
        val q=identity(r.clientInstanceId,r.deviceId,r.saleId,r.operationId)
        return when(kind) { SaleCommandKind.Commit -> SaleCommand.Commit(r.commandId,q,reason(r.reason));SaleCommandKind.Release -> SaleCommand.Release(r.commandId,q,reason(r.reason));else -> throw IllegalArgumentException("unsupported command") }
    }
}
enum class SaleAdmissionOutcomeV2 { Accepted, NotFound, Unavailable, Unknown, Rejected }
data class SaleAdmissionV2Response(val outcome: SaleAdmissionOutcomeV2,val receipt: SaleAdmissionReceiptV2Response?=null,val failure: String?=null)
data class SaleAdmissionReceiptV2Response(val commandId: UUID,val kind: SaleCommandKind,val payloadHash: String,val actorId: Long,
    val cashSessionId: Long,val clientInstanceId: String,val deviceId: String,val saleId: String,val operationId: String,
    val intentId: Long,val outboxId: Long,val acceptedAt: java.time.Instant)
object SaleV2ResponseTranslator {
    fun translate(result: SaleCommandResult): SaleAdmissionV2Response=when(result) {
        is SaleCommandResult.Accepted -> result.receipt.let { r -> SaleAdmissionV2Response(SaleAdmissionOutcomeV2.Accepted,SaleAdmissionReceiptV2Response(r.commandId,r.kind,r.payloadHash,r.actorId,r.cashSessionId,r.identity.clientInstanceId,r.identity.deviceId,r.identity.saleId,r.identity.operationId,r.intentId,r.outboxId,r.acceptedAt)) }
        SaleCommandResult.NotFound -> SaleAdmissionV2Response(SaleAdmissionOutcomeV2.NotFound)
        SaleCommandResult.Unavailable -> SaleAdmissionV2Response(SaleAdmissionOutcomeV2.Unavailable)
        SaleCommandResult.Unknown -> SaleAdmissionV2Response(SaleAdmissionOutcomeV2.Unknown)
        is SaleCommandResult.Rejected -> SaleAdmissionV2Response(SaleAdmissionOutcomeV2.Rejected,failure=result.failure.wire)
    }
}
