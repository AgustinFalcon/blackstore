package com.blackstore.domain.sales

import com.blackstore.domain.identity.StaffPermission
import com.blackstore.domain.model.OperationQuadruple
import java.time.Instant
import java.util.UUID

enum class SaleCommandKind(val permission: StaffPermission) {
    Reserve(StaffPermission.SaleReserve), Commit(StaffPermission.SaleCommit), Release(StaffPermission.SaleRelease), Unknown(StaffPermission.Unknown);
    companion object { fun fromWire(raw: String?) = entries.firstOrNull { it.name == raw } ?: Unknown }
}

/** Accepted describes local durable admission only, never remote terminality. */
data class SaleCommandAdmissionReceipt(val commandId: UUID, val kind: SaleCommandKind, val payloadHash: String,
    val actorId: Long, val cashSessionId: Long, val identity: OperationQuadruple, val intentId: Long,
    val outboxId: Long, val acceptedAt: Instant) {
    init {
        require(kind != SaleCommandKind.Unknown && actorId > 0 && cashSessionId > 0 && intentId > 0 && outboxId > 0)
        require(payloadHash.matches(Regex("[0-9a-f]{64}")))
    }
}
sealed interface SaleCommandResult {
    data class Accepted(val receipt: SaleCommandAdmissionReceipt, val replay: Boolean = false) : SaleCommandResult
    data object NotFound : SaleCommandResult
    data object Unavailable : SaleCommandResult
    data object Unknown : SaleCommandResult
    data class Rejected(val failure: SaleCommandFailure, val lineFailures: List<SaleLineFailure> = emptyList()) : SaleCommandResult
}
enum class SaleLineFailureCode {
    VariantMissing, CatalogTextInvalid, SkuMismatch, ProductNameMismatch, PriceVersionMismatch, PriceMismatch, DiscountNotAllowed, Unknown;
    companion object { fun fromWire(raw: String?) = entries.firstOrNull { it.name == raw } ?: Unknown }
}
data class SaleLineFailure(val variantId: String, val code: SaleLineFailureCode) {
    init { require(variantId.isNotEmpty() && code!=SaleLineFailureCode.Unknown) }
}
enum class SaleCommandFailure(val status: Int, val wire: String) {
    Validation(400,"VALIDATION"), Forbidden(403,"FORBIDDEN"), NotVisible(404,"NOT_VISIBLE"),
    PayloadMismatch(409,"PAYLOAD_MISMATCH"), ExistingOperationCommand(409,"EXISTING_OPERATION_COMMAND"),
    Closed(409,"CLOSED"), TransitionConflict(409,"TRANSITION_CONFLICT"), NotActivated(409,"NOT_ACTIVATED"),
    Paused(409,"PAUSED"), LegacyContractDisabled(409,"LEGACY_CONTRACT_DISABLED"), Unavailable(503,"UNAVAILABLE"), Unknown(503,"UNKNOWN")
}
sealed interface SaleCommand {
    val commandId: UUID
    val identity: OperationQuadruple
    val reason: String?
    val kind: SaleCommandKind
    data class Reserve(override val commandId: UUID, override val identity: OperationQuadruple,
        val cashSessionId: Long, val variantId: String, val quantity: Int, val expectedPriceVersion: String,
        val line: TicketLine, override val reason: String?) : SaleCommand {
        override val kind = SaleCommandKind.Reserve
        init { require(cashSessionId > 0 && quantity > 0 && variantId.isNotBlank() && expectedPriceVersion.isNotBlank()); require(line.quantity == quantity) }
    }
    data class ReserveMultiline(override val commandId: UUID, override val identity: OperationQuadruple,
        val cashSessionId: Long, val lines: List<SaleReservationLine>, override val reason: String?) : SaleCommand {
        override val kind = SaleCommandKind.Reserve
        init {
            require(cashSessionId > 0 && lines.size in 1..100)
            require(lines.map { it.variantId }.distinct().size == lines.size)
            SaleTextV2.ref(identity.clientInstanceId); SaleTextV2.ref(identity.deviceId)
            SaleTextV2.ref(identity.saleId); SaleTextV2.ref(identity.operationId)
            require(reason == SaleTextV2.reason(reason))
            MoneyPolicy.normalize(lines.fold(java.math.BigDecimal.ZERO) { total, line ->
                total + line.ticketLine.effectiveUnitPrice.multiply(line.quantity.toBigDecimal())
            })
        }
    }
    data class Commit(override val commandId: UUID, override val identity: OperationQuadruple, override val reason: String?) : SaleCommand { override val kind = SaleCommandKind.Commit }
    data class Release(override val commandId: UUID, override val identity: OperationQuadruple, override val reason: String?) : SaleCommand { override val kind = SaleCommandKind.Release }
}

data class SaleReservationLine(val variantId: String, val quantity: Int, val expectedPriceVersion: String, val ticketLine: TicketLine) {
    init {
        SaleTextV2.ref(variantId); SaleTextV2.ref(expectedPriceVersion); SaleTextV2.ref(ticketLine.sku)
        require(ticketLine.variantId==null || ticketLine.variantId==variantId)
        require(ticketLine.priceVersion==null || ticketLine.priceVersion==expectedPriceVersion)
        require(ticketLine.productName == SaleTextV2.name(ticketLine.productName))
        require(quantity > 0 && quantity == ticketLine.quantity && ticketLine.discountAmount.signum() == 0)
    }
}
