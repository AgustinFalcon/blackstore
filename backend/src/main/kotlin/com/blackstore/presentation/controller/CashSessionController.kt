package com.blackstore.presentation.controller

import com.blackstore.application.cash.CashSessionApplicationService
import com.blackstore.application.dto.response.BaseResponse
import com.blackstore.domain.cash.CashSessionStatus
import com.blackstore.domain.cash.StaffRole
import com.blackstore.infrastructure.exception.GlobalExceptionHandler
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.PositiveOrZero
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal

@RestController
@RequestMapping("/api/v1/cash-sessions")
class CashSessionController(
    private val cashSessionApplicationService: CashSessionApplicationService,
) {
    @GetMapping
    fun list(request: HttpServletRequest): ResponseEntity<BaseResponse<List<CashSessionResponse>>> =
        ResponseEntity.ok(
            BaseResponse.success(
                cashSessionApplicationService.list().map { it.toResponse() },
                traceId(request),
            ),
        )

    @PostMapping
    fun open(
        request: HttpServletRequest,
        @RequestHeader("X-Actor-Id") actorId: Long,
        @RequestHeader("X-Role") role: StaffRole,
        @Valid @RequestBody body: OpenCashSessionRequest,
    ): ResponseEntity<BaseResponse<CashSessionResponse>> =
        ResponseEntity.ok(
            BaseResponse.success(
                cashSessionApplicationService
                    .open(actorId, role, body.terminalId, body.cashierId, body.openingCash)
                    .toResponse(),
                traceId(request),
            ),
        )

    @PostMapping("/{sessionId}/close")
    fun close(
        request: HttpServletRequest,
        @PathVariable sessionId: Long,
        @RequestHeader("X-Actor-Id") actorId: Long,
        @RequestHeader("X-Role") role: StaffRole,
        @Valid @RequestBody body: CloseCashSessionRequest,
    ): ResponseEntity<BaseResponse<CashSessionResponse>> =
        ResponseEntity.ok(
            BaseResponse.success(
                cashSessionApplicationService.close(actorId, role, sessionId, body.declared, body.reason).toResponse(),
                traceId(request),
            ),
        )

    private fun traceId(request: HttpServletRequest): String =
        request.getHeader(GlobalExceptionHandler.TRACE_HEADER)?.takeIf { it.isNotBlank() }
            ?: java.util.UUID.randomUUID().toString()
}

data class OpenCashSessionRequest(
    @field:NotNull val terminalId: Long,
    @field:NotNull val cashierId: Long,
    @field:NotNull @field:PositiveOrZero val openingCash: BigDecimal,
)

data class CloseCashSessionRequest(
    @field:NotNull @field:PositiveOrZero val declared: BigDecimal,
    @field:NotNull val reason: String,
)

data class CashSessionResponse(
    val id: Long,
    val terminalId: Long,
    val cashierId: Long,
    val status: CashSessionStatus,
    val openingCash: BigDecimal,
    val closingCashDeclared: BigDecimal?,
)

private fun com.blackstore.domain.cash.CashSession.toResponse() =
    CashSessionResponse(id, terminalId, cashierId, status, openingCash, closingCashDeclared)
