package com.blackstore.application.dto.sales

import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.sales.*
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.databind.JsonNode
import java.math.BigDecimal
import java.util.UUID

data class SaleReserveV3LineRequest(val variantId: String, val quantity: JsonNode, val expectedPriceVersion: String,
    val sku: String, val productName: String, val originalUnitPrice: BigDecimal, val discountAmount: BigDecimal) {
    @JsonAnySetter fun rejectUnknown(name: String, value: Any?) { throw IllegalArgumentException("unsupported sale line field") }
}

data class SaleReserveV3Request(val commandId: UUID, val clientInstanceId: String, val deviceId: String,
    val saleId: String, val operationId: String, val cashSessionId: Long, val lines: List<SaleReserveV3LineRequest>, val reason: String? = null) {
    @JsonAnySetter fun rejectUnknown(name: String, value: Any?) { throw IllegalArgumentException("unsupported sale field") }
}

object SaleV3RequestTranslator {
    private fun id(raw: String): String {
        SaleTextV2.validate(raw)
        require(UUID.fromString(raw).toString() == raw)
        return raw
    }
    fun reserve(r: SaleReserveV3Request): SaleCommand.ReserveMultiline {
        // Validate every raw hashed text before normalization or object construction.
        listOf(r.clientInstanceId, r.deviceId, r.saleId, r.operationId).forEach(SaleTextV2::validate)
        r.reason?.let(SaleTextV2::validate)
        r.lines.forEach { line -> listOf(line.variantId, line.expectedPriceVersion, line.sku, line.productName).forEach(SaleTextV2::validate) }
        val identity = OperationQuadruple(id(r.clientInstanceId), SaleTextV2.ref(r.deviceId), SaleTextV2.ref(r.saleId), id(r.operationId))
        require(r.lines.size in 1..100)
        val lines = r.lines.map { line ->
            require(line.quantity.isIntegralNumber && line.quantity.canConvertToInt())
            val quantity = line.quantity.intValue()
            SaleReservationLine(SaleTextV2.ref(line.variantId), quantity, SaleTextV2.ref(line.expectedPriceVersion),
                TicketLine(SaleTextV2.ref(line.sku), SaleTextV2.name(line.productName), quantity,
                    MoneyPolicy.normalize(line.originalUnitPrice), MoneyPolicy.normalize(line.discountAmount),line.variantId,line.expectedPriceVersion))
        }
        return SaleCommand.ReserveMultiline(r.commandId, identity, r.cashSessionId, lines, SaleTextV2.reason(r.reason))
    }
}

data class SaleAdmissionReceiptV3Response(val commandId: UUID, val kind: SaleCommandKind, val payloadHash: String,
    val actorId: Long, val cashSessionId: Long, val clientInstanceId: String, val deviceId: String, val saleId: String,
    val operationId: String, val intentId: Long, val outboxId: Long, val acceptedAt: java.time.Instant,
    val fingerprintVersion: String)
data class SaleAdmissionV3Response(val outcome: SaleAdmissionOutcomeV2, val receipt: SaleAdmissionReceiptV3Response? = null,
    val failure: String? = null, val lineFailures: List<SaleLineFailureV3Response> = emptyList())
data class SaleLineFailureV3Response(val variantId: String,val code: SaleLineFailureCode)
