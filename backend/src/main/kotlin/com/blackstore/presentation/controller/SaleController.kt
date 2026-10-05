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
import java.math.BigDecimal

@RestController
@RequestMapping("/api/v1/sales")
class SaleController(
    private val localSaleSagaService: LocalSaleSagaService,
) {
    @PostMapping("/reservations")
    fun reserve(
        request: HttpServletRequest,
        @Valid @RequestBody body: ReserveSaleRequest,
    ): ResponseEntity<BaseResponse<ReserveSaleResponse>> {
        val saga =
            localSaleSagaService.beginReserve(
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
        val saga = localSaleSagaService.stored(operationId)
        val traceId =
            request.getHeader(GlobalExceptionHandler.TRACE_HEADER)?.takeIf { it.isNotBlank() }
                ?: java.util.UUID.randomUUID().toString()
        if (saga == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                BaseResponse.error(
                    httpCode = HttpCode.NOT_FOUND,
                    traceId = traceId,
                    errorCode = "NOT_FOUND",
                    message = "sale is not in this process",
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
    ): ResponseEntity<BaseResponse<ReserveSaleResponse>> = sagaResponse(request, localSaleSagaService.commit(operationId))

    @PostMapping("/{operationId}/release")
    fun release(
        request: HttpServletRequest,
        @PathVariable operationId: String,
    ): ResponseEntity<BaseResponse<ReserveSaleResponse>> = sagaResponse(request, localSaleSagaService.release(operationId))

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

private object PaymentCoverageWire {
    fun toWire(value: PaymentCoverage): String = when (value) {
        PaymentCoverage.Unpaid -> "UNPAID"
        PaymentCoverage.Partial -> "PARTIAL"
        PaymentCoverage.Paid -> "PAID"
        PaymentCoverage.InvalidUnknown -> "INVALID_UNKNOWN"
    }
}
