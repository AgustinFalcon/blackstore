package com.blackstore.presentation.controller

import com.blackstore.application.dto.response.BaseResponse
import com.blackstore.application.dto.response.HttpCode
import com.blackstore.application.sales.LocalSaleSagaService
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.port.out.storecore.ReserveLineCommand
import com.blackstore.domain.sales.SaleStatus
import com.blackstore.domain.sales.TicketLine
import com.blackstore.domain.sales.PaymentCoverage
import com.blackstore.infrastructure.exception.GlobalExceptionHandler
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.springframework.http.ResponseEntity
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RequestParam
import java.math.BigDecimal
import com.blackstore.infrastructure.identity.staffSession

@RestController
@RequestMapping("/api/v1/sales")
class SaleController(
    private val localSaleSagaService: LocalSaleSagaService,
) {
    @GetMapping
    fun list(request: HttpServletRequest, @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "50") limit: Int, @RequestParam(required = false) state: String?): ResponseEntity<BaseResponse<DurableSaleListResponse>> {
        val after = cursor?.toLongOrNull() ?: if (cursor == null) 0L else throw IllegalArgumentException("invalid cursor")
        require(after >= 0 && limit in 1..100)
        val filter = state?.let { raw -> com.blackstore.domain.sales.DurableSaleState.entries.firstOrNull { it.name == raw }
            ?: throw IllegalArgumentException("invalid sale state") }
        val page = localSaleSagaService.list(request.staffSession().staff, after, limit, filter)
        return ResponseEntity.ok(BaseResponse.success(DurableSaleListResponse(page.items.map(::durableResponse), page.nextCursor?.toString()), trace(request)))
    }

    @GetMapping("/operations/{operationId}")
    fun durableDetail(request: HttpServletRequest, @PathVariable operationId: String): ResponseEntity<BaseResponse<DurableSaleDetailResponse>> {
        val view = localSaleSagaService.detail(request.staffSession().staff, operationId)
            ?: throw com.blackstore.domain.identity.StaffSecurityException(com.blackstore.domain.identity.StaffSecurityFailure.NOT_FOUND)
        return ResponseEntity.ok(BaseResponse.success(durableResponse(view), trace(request)))
    }

    private fun trace(request: HttpServletRequest) = request.getHeader(GlobalExceptionHandler.TRACE_HEADER)?.takeIf { it.isNotBlank() }
        ?: java.util.UUID.randomUUID().toString()

    private fun durableResponse(view: com.blackstore.application.sales.DurableSaleView): DurableSaleDetailResponse {
        val sale = view.sale.saga
        val kind = when (sale.status) {
            SaleStatus.PENDING_RESERVATION -> com.blackstore.domain.sales.CommandKind.RESERVE
            SaleStatus.COMMIT_PENDING -> com.blackstore.domain.sales.CommandKind.COMMIT
            SaleStatus.RELEASE_PENDING -> com.blackstore.domain.sales.CommandKind.RELEASE
            else -> null
        }
        return DurableSaleDetailResponse(sale.quadruple.operationId, sale.quadruple.clientInstanceId, sale.quadruple.deviceId,
            sale.quadruple.saleId, sale.cashSessionId, view.cashierId, view.sale.state, sale.evidence?.receipt, sale.evidence?.reservationRef,
            view.snapshot.evidenceValid, view.snapshot.totalAmount, view.snapshot.pendingAmount,
            PaymentCoverageWire.toWire(view.snapshot.paymentCoverage), view.snapshot.hasPaymentHistory,
            sale.blockSameOperationRepost || view.sale.state in setOf(com.blackstore.domain.sales.DurableSaleState.UNKNOWN,
                com.blackstore.domain.sales.DurableSaleState.LEGACY_INCOMPLETE, com.blackstore.domain.sales.DurableSaleState.RECONCILIATION_REQUIRED),
            sale.retired, sale.lines.map { DurableSaleLineResponse(it.sku, it.productName, it.quantity, it.effectiveUnitPrice.multiply(it.quantity.toBigDecimal())) },
            view.payments.map { DurableSalePaymentResponse(it.paymentId, it.status, it.method, it.amount, it.feeAmount) },
            kind?.let(::DurableSalePendingCommandResponse), view.allowedActions)
    }
    @PostMapping("/reservations")
    fun reserve(
        request: HttpServletRequest,
        @Valid @RequestBody body: ReserveSaleRequest,
    ): ResponseEntity<BaseResponse<ReserveSaleResponse>> {
        val saga =
            localSaleSagaService.beginReserve(
                staff = request.staffSession().staff,
                quadruple =
                    OperationQuadruple(
                        clientInstanceId = body.clientInstanceId,
                        deviceId = body.deviceId,
                        saleId = body.saleId,
                        operationId = body.operationId,
                    ),
                cashSessionId = body.cashSessionId,
                lines = listOf(ReserveLineCommand(body.variantId, body.quantity, body.expectedPriceVersion)),
                ticketLines = body.ticketLines(),
                now = java.time.Instant.now(),
                reason = body.reason,
            )
        val traceId =
            request.getHeader(GlobalExceptionHandler.TRACE_HEADER)?.takeIf { it.isNotBlank() }
                ?: java.util.UUID.randomUUID().toString()
        return ResponseEntity.ok(
            BaseResponse.success(
                response(saga),
                traceId,
            ),
        )
    }

    @GetMapping("/{operationId}")
    fun get(
        request: HttpServletRequest,
        @PathVariable operationId: String,
    ): ResponseEntity<BaseResponse<ReserveSaleResponse>> {
        val saga = localSaleSagaService.stored(request.staffSession().staff, operationId)
        val traceId =
            request.getHeader(GlobalExceptionHandler.TRACE_HEADER)?.takeIf { it.isNotBlank() }
                ?: java.util.UUID.randomUUID().toString()
        if (saga == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                BaseResponse.error(
                    httpCode = HttpCode.NOT_FOUND,
                    traceId = traceId,
                    errorCode = "NOT_FOUND",
                    message = "sale not found",
                    retryable = false,
                ),
            )
        }
        return sagaResponse(request, saga)
    }

    @PostMapping("/{operationId}/commit")
    fun commit(
        request: HttpServletRequest,
        @PathVariable operationId: String,
        @RequestBody(required = false) body: SaleActionRequest? = null,
    ): ResponseEntity<BaseResponse<ReserveSaleResponse>> = sagaResponse(request, localSaleSagaService.commit(request.staffSession().staff, operationId, body?.reason))

    @PostMapping("/{operationId}/release")
    fun release(
        request: HttpServletRequest,
        @PathVariable operationId: String,
        @RequestBody(required = false) body: SaleActionRequest? = null,
    ): ResponseEntity<BaseResponse<ReserveSaleResponse>> = sagaResponse(request, localSaleSagaService.release(request.staffSession().staff, operationId, body?.reason))

    private fun sagaResponse(
        request: HttpServletRequest,
        saga: com.blackstore.domain.sales.SaleSaga,
    ): ResponseEntity<BaseResponse<ReserveSaleResponse>> {
        val traceId =
            request.getHeader(GlobalExceptionHandler.TRACE_HEADER)?.takeIf { it.isNotBlank() }
                ?: java.util.UUID.randomUUID().toString()
        return ResponseEntity.ok(
            BaseResponse.success(
                response(saga),
                traceId,
            ),
        )
    }

    private fun response(saga: com.blackstore.domain.sales.SaleSaga): ReserveSaleResponse {
        val snapshot = localSaleSagaService.paymentSnapshot(saga)
        return ReserveSaleResponse(saga.quadruple.operationId, saga.status, saga.evidence?.receipt, saga.evidence?.reservationRef,
            saga.quadruple.clientInstanceId, saga.quadruple.deviceId, saga.quadruple.saleId,
            snapshot.evidenceValid, snapshot.totalAmount, snapshot.pendingAmount, PaymentCoverageWire.toWire(snapshot.paymentCoverage), snapshot.hasPaymentHistory,
            saga.blockSameOperationRepost, saga.retired)
    }
}

data class ReserveSaleRequest(
    @field:NotBlank val clientInstanceId: String,
    @field:NotBlank val deviceId: String,
    @field:NotBlank val saleId: String,
    @field:NotBlank val operationId: String,
    val cashSessionId: Long,
    @field:NotBlank val variantId: String,
    @field:Positive val quantity: Int,
    @field:NotBlank val expectedPriceVersion: String,
    val sku: String? = null,
    val productName: String? = null,
    val originalUnitPrice: BigDecimal? = null,
    val discountAmount: BigDecimal? = null,
    val reason: String? = null,
) {
    fun ticketLines(): List<TicketLine> {
        val price = originalUnitPrice ?: return emptyList()
        return listOf(
            TicketLine(
                sku = sku?.takeIf { it.isNotBlank() } ?: variantId,
                productName = productName?.takeIf { it.isNotBlank() } ?: variantId,
                quantity = quantity,
                originalUnitPrice = price,
                discountAmount = discountAmount ?: BigDecimal.ZERO,
            ),
        )
    }
}

data class ReserveSaleResponse(
    val operationId: String,
    val status: SaleStatus,
    val receipt: String?,
    val reservationRef: String?,
    val clientInstanceId: String,
    val deviceId: String,
    val saleId: String,
    val evidenceValid: Boolean,
    val totalAmount: BigDecimal?,
    val pendingAmount: BigDecimal?,
    val paymentCoverage: String,
    val hasPaymentHistory: Boolean,
    val blocked: Boolean,
    val retired: Boolean,
)

data class SaleActionRequest(val reason: String? = null)

data class DurableSaleListResponse(val items: List<DurableSaleDetailResponse>, val nextCursor: String?)
data class DurableSaleLineResponse(val sku: String, val productName: String, val quantity: Int, val totalAmount: BigDecimal)
data class DurableSalePaymentResponse(val paymentId: Long, val status: com.blackstore.domain.sales.PaymentStatus,
    val method: com.blackstore.domain.sales.PaymentMethod, val amount: BigDecimal?, val feeAmount: BigDecimal?)
data class DurableSalePendingCommandResponse(val kind: com.blackstore.domain.sales.CommandKind)
data class DurableSaleDetailResponse(val operationId: String, val clientInstanceId: String, val deviceId: String, val saleId: String,
    val cashSessionId: Long, val cashierId: Long, val status: com.blackstore.domain.sales.DurableSaleState,
    val receipt: String?, val reservationRef: String?, val evidenceValid: Boolean, val totalAmount: BigDecimal?,
    val pendingAmount: BigDecimal?, val paymentCoverage: String, val hasPaymentHistory: Boolean, val blocked: Boolean, val retired: Boolean,
    val lines: List<DurableSaleLineResponse>, val payments: List<DurableSalePaymentResponse>,
    val pendingCommand: DurableSalePendingCommandResponse?, val allowedActions: Set<com.blackstore.domain.sales.SaleAllowedAction>)

private object PaymentCoverageWire {
    fun toWire(value: PaymentCoverage): String = when (value) {
        PaymentCoverage.Unpaid -> "UNPAID"
        PaymentCoverage.Partial -> "PARTIAL"
        PaymentCoverage.Paid -> "PAID"
        PaymentCoverage.InvalidUnknown -> "INVALID_UNKNOWN"
    }
}
