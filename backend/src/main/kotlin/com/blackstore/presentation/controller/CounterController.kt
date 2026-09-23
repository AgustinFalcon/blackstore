package com.blackstore.presentation.controller

import com.blackstore.application.counter.CounterApplicationService
import com.blackstore.application.dto.response.BaseResponse
import com.blackstore.domain.cash.StaffRole
import com.blackstore.domain.port.out.workspace.WorkspaceQuery
import com.blackstore.domain.reports.ShiftFigures
import com.blackstore.domain.sales.PaymentMethod
import com.blackstore.infrastructure.exception.GlobalExceptionHandler
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
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
@RequestMapping("/api/v1")
class CounterController(
    private val counterApplicationService: CounterApplicationService,
    private val workspaceQuery: WorkspaceQuery,
) {
    @GetMapping("/workspace")
    fun workspace(request: HttpServletRequest): ResponseEntity<BaseResponse<WorkspaceResponse>> {
        val current = workspaceQuery.current()
        return ResponseEntity.ok(
            BaseResponse.success(
                WorkspaceResponse(current.terminalId, current.cashierId, current.persistence),
                traceId(request),
            ),
        )
    }

    @PostMapping("/payments")
    fun pay(
        request: HttpServletRequest,
        @Valid @RequestBody body: CapturePaymentRequest,
    ): ResponseEntity<BaseResponse<PaymentResponse>> {
        val payment =
            counterApplicationService.capture(
                body.operationId,
                body.method,
                body.amount,
                body.feeAmount,
            )
        return ResponseEntity.ok(
            BaseResponse.success(
                PaymentResponse(payment.id, payment.status.name, payment.amount, payment.feeAmount),
                traceId(request),
            ),
        )
    }

    @PostMapping("/payments/{paymentId}/reversals")
    fun reverse(
        request: HttpServletRequest,
        @PathVariable paymentId: Long,
        @RequestHeader("X-Actor-Id") actorId: Long,
        @RequestHeader("X-Role") role: StaffRole,
        @Valid @RequestBody body: ReversalRequest,
    ): ResponseEntity<BaseResponse<PaymentResponse>> {
        val payment =
            counterApplicationService.reverse(
                operationId = body.operationId,
                paymentId = paymentId,
                actorId = actorId,
                role = role,
                reason = body.reason,
                evidenceRef = body.evidenceRef,
            )
        return ResponseEntity.ok(
            BaseResponse.success(
                PaymentResponse(payment.id, payment.status.name, payment.amount, payment.feeAmount),
                traceId(request),
            ),
        )
    }

    @PostMapping("/expenses")
    fun expense(
        request: HttpServletRequest,
        @RequestHeader("X-Actor-Id") actorId: Long,
        @RequestHeader("X-Role") role: StaffRole,
        @Valid @RequestBody body: ExpenseRequest,
    ): ResponseEntity<BaseResponse<ExpenseResponse>> {
        val expense =
            counterApplicationService.addExpense(
                actorId = actorId,
                role = role,
                cashSessionId = body.cashSessionId,
                category = body.category,
                amount = body.amount,
                reason = body.reason,
                method = body.method,
            )
        return ResponseEntity.ok(
            BaseResponse.success(ExpenseResponse(expense.id, expense.amount, expense.category), traceId(request)),
        )
    }

    @GetMapping("/reports/shift")
    fun shift(request: HttpServletRequest): ResponseEntity<BaseResponse<ShiftReportResponse>> =
        report(request, counterApplicationService.shiftReport())

    @GetMapping("/reports/daily")
    fun daily(request: HttpServletRequest): ResponseEntity<BaseResponse<ShiftReportResponse>> =
        report(request, counterApplicationService.dailyReport())

    private fun report(
        request: HttpServletRequest,
        report: com.blackstore.application.counter.ShiftReport,
    ): ResponseEntity<BaseResponse<ShiftReportResponse>> =
        ResponseEntity.ok(
            BaseResponse.success(
                report.figures.toResponse(report.formulaName, report.fiscalResult, report.periodKind),
                traceId(request),
            ),
        )

    private fun traceId(request: HttpServletRequest): String =
        request.getHeader(GlobalExceptionHandler.TRACE_HEADER)?.takeIf { it.isNotBlank() }
            ?: java.util.UUID.randomUUID().toString()
}

data class WorkspaceResponse(val terminalId: Long, val cashierId: Long, val persistence: String)

data class CapturePaymentRequest(
    @field:NotBlank val operationId: String,
    @field:NotNull val method: PaymentMethod,
    @field:NotNull @field:Positive val amount: BigDecimal,
    @field:NotNull val feeAmount: BigDecimal,
)

data class PaymentResponse(val id: Long, val status: String, val amount: BigDecimal, val feeAmount: BigDecimal)

data class ReversalRequest(
    @field:NotBlank val operationId: String,
    @field:NotBlank val reason: String,
    @field:NotBlank val evidenceRef: String,
)

data class ExpenseRequest(
    val cashSessionId: Long,
    @field:NotBlank val category: String,
    @field:NotNull @field:Positive val amount: BigDecimal,
    @field:NotBlank val reason: String,
    @field:NotNull val method: PaymentMethod,
)

data class ExpenseResponse(val id: Long, val amount: BigDecimal, val category: String)

data class ShiftReportResponse(
    val grossSales: BigDecimal,
    val discounts: BigDecimal,
    val netSales: BigDecimal,
    val refunds: BigDecimal,
    val collected: BigDecimal,
    val feesPaid: BigDecimal,
    val expensesPaid: BigDecimal,
    val operatingCashFlow: BigDecimal,
    val margin: BigDecimal?,
    val formulaName: String,
    val fiscalResult: Boolean,
    val periodKind: String,
)

private fun ShiftFigures.toResponse(formulaName: String, fiscalResult: Boolean, periodKind: String) =
    ShiftReportResponse(
        grossSales,
        discounts,
        netSales,
        refunds,
        collected,
        feesPaid,
        expensesPaid,
        operatingCashFlow,
        margin,
        formulaName,
        fiscalResult,
        periodKind,
    )
