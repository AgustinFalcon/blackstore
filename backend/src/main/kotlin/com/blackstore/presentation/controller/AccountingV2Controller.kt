package com.blackstore.presentation.controller

import com.blackstore.application.accounting.AccountingApplicationService
import com.blackstore.application.dto.accounting.*
import com.blackstore.application.dto.response.BaseResponse
import com.blackstore.domain.accounting.AccountingCommandFailure
import com.blackstore.domain.accounting.AccountingCommandResult
import com.blackstore.domain.port.out.accounting.AccountingMutationOutcome
import com.blackstore.infrastructure.identity.staffSession
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v2")
class AccountingV2Controller(private val accounting: AccountingApplicationService) {
    @PostMapping("/cash-sessions")
    fun open(request: HttpServletRequest, @RequestBody body: CashSessionOpenV2Request) =
        mutate(request) { AccountingV2RequestTranslator.translate(body) }

    @PostMapping("/expenses")
    fun expense(request: HttpServletRequest, @RequestBody body: ExpenseRecordV2Request) =
        mutate(request) { AccountingV2RequestTranslator.translate(body) }

    @PostMapping("/payments")
    fun capture(request: HttpServletRequest, @RequestBody body: PaymentCaptureV2Request) =
        mutate(request) { AccountingV2RequestTranslator.translate(body) }

    @PostMapping("/payments/{paymentId}/reversals")
    fun reverse(request: HttpServletRequest, @PathVariable paymentId: Long, @RequestBody body: PaymentReverseV2Request) =
        mutate(request) {
            require(paymentId == body.originalPaymentId)
            AccountingV2RequestTranslator.translate(body)
        }

    @GetMapping("/accounting/commands/{commandId}")
    fun receipt(request: HttpServletRequest, @PathVariable commandId: UUID): ResponseEntity<BaseResponse<AccountingCommandReceiptV2Response>> {
        val result = accounting.receipt(request.staffSession().staff, commandId)
        return respond(request, AccountingV2ResponseTranslator.translate(result))
    }

    private fun mutate(request: HttpServletRequest, translate: () -> com.blackstore.domain.accounting.AccountingCommandDraft): ResponseEntity<BaseResponse<AccountingCommandReceiptV2Response>> {
        // Resolve the authenticated context before request translation; no wire actor is trusted.
        val staff = request.staffSession().staff
        val command = try { translate() } catch (_: IllegalArgumentException) {
            return rejected(request, AccountingCommandFailure.Validation)
        }
        return when (val result = accounting.execute(staff, command)) {
            is AccountingMutationOutcome.Applied -> respond(request,
                AccountingV2ResponseTranslator.translate(AccountingCommandResult.Committed(result.receipt)))
            is AccountingMutationOutcome.Rejected -> rejected(request, result.failure)
        }
    }

    private fun rejected(request: HttpServletRequest, failure: AccountingCommandFailure): ResponseEntity<BaseResponse<AccountingCommandReceiptV2Response>> {
        return respond(request, AccountingV2ResponseTranslator.rejected(failure))
    }

    private fun respond(request: HttpServletRequest, data: AccountingCommandReceiptV2Response): ResponseEntity<BaseResponse<AccountingCommandReceiptV2Response>> {
        val status = data.failure?.status ?: data.outcome.status
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(
            BaseResponse(status, request.getHeader("X-Trace-Id")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                data, if (status == 200) null else "Accounting operation unavailable or denied",
                if (status == 200) null else data.failure?.wire ?: data.outcome.wire,
                if (status == 200) null else false))
    }
}
