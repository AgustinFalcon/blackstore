package com.blackstore.presentation.controller

import com.blackstore.application.dto.response.BaseResponse
import com.blackstore.application.sales.LocalSaleSagaService
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.port.out.storecore.ReserveLineCommand
import com.blackstore.domain.sales.SaleStatus
import com.blackstore.domain.sales.TicketLine
import com.blackstore.infrastructure.exception.GlobalExceptionHandler
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.springframework.http.ResponseEntity
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
                ReserveSaleResponse(
                    operationId = saga.quadruple.operationId,
                    status = saga.status,
                    receipt = saga.evidence?.receipt,
                    reservationRef = saga.evidence?.reservationRef,
                ),
                traceId,
            ),
        )
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
                ReserveSaleResponse(
                    operationId = saga.quadruple.operationId,
                    status = saga.status,
                    receipt = saga.evidence?.receipt,
                    reservationRef = saga.evidence?.reservationRef,
                ),
                traceId,
            ),
        )
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
)
