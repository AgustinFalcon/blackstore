package com.blackstore.presentation.controller

import com.blackstore.application.accounting.ExpenseCommandProjectionService
import com.blackstore.application.dto.response.BaseResponse
import com.blackstore.application.dto.accounting.ExpenseOperationV2
import com.blackstore.application.dto.accounting.CompletenessStateV2
import com.blackstore.application.dto.accounting.CompletenessCauseV2
import com.blackstore.domain.accounting.*
import com.fasterxml.jackson.annotation.JsonValue
import com.blackstore.infrastructure.identity.staffSession
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

enum class ExpenseProjectionState(@get:JsonValue val wire: String, val status: Int) {
    Found("FOUND", 200), NotFound("NOT_FOUND", 404), Unavailable("UNAVAILABLE", 503), Unknown("UNKNOWN", 503)
}
data class ExpenseCommandProjectionResponse(val state: ExpenseProjectionState, val projection: ExpenseCommandProjectionDto?)
data class ExpenseCommandProjectionDto(val commandId: UUID, val kind: AccountingCommandKind, val operation: ExpenseOperationV2,
    val cashSessionId: Long, val expenseId: Long, val settlementId: Long?, val ledgerEventIds: List<Long>,
    val expense: ExpenseProjectionExpense, val settlement: ExpenseProjectionSettlement?, val accountingEvidence: ExpenseProjectionEvidenceDto)
data class ExpenseProjectionPostingDto(val id: Long, val kind: LedgerEventKind, val component: LedgerComponent,
    val paymentMethod: com.blackstore.domain.sales.PaymentMethod, val amount: java.math.BigDecimal, val origin: LedgerOrigin,
    val occurredAt: java.time.Instant, val actorId: Long, val commandId: UUID, val cashSessionId: Long, val expenseId: Long?, val accountingVersion: Int)
data class ExpenseProjectionEvidenceDto(val committedAt: java.time.Instant, val postings: List<ExpenseProjectionPostingDto>,
    val accountingVersion: Int, val completeness: CompletenessStateV2, val causes: List<CompletenessCauseV2>, val snapshot: ExpenseProjectionSnapshot)
object ExpenseCommandProjectionResponseTranslator {
    fun translate(result: ExpenseCommandProjectionResult): ExpenseCommandProjectionResponse = when (result) {
        is ExpenseCommandProjectionResult.Found -> ExpenseCommandProjectionResponse(ExpenseProjectionState.Found, projection(result.projection))
        ExpenseCommandProjectionResult.NotFound -> ExpenseCommandProjectionResponse(ExpenseProjectionState.NotFound, null)
        ExpenseCommandProjectionResult.Unavailable -> ExpenseCommandProjectionResponse(ExpenseProjectionState.Unavailable, null)
        ExpenseCommandProjectionResult.Unknown -> ExpenseCommandProjectionResponse(ExpenseProjectionState.Unknown, null)
    }
    private fun projection(value: ExpenseCommandProjection): ExpenseCommandProjectionDto = ExpenseCommandProjectionDto(value.commandId, value.kind,
        when (value.operation) {
            ExpenseProjectionOperation.Accrue -> ExpenseOperationV2.Accrue
            ExpenseProjectionOperation.AccrueAndSettle -> ExpenseOperationV2.AccrueAndSettle
            ExpenseProjectionOperation.SettleExisting -> ExpenseOperationV2.SettleExisting
            ExpenseProjectionOperation.Unknown -> ExpenseOperationV2.Unknown
        }, value.cashSessionId, value.expenseId, value.settlementId, value.ledgerEventIds, value.expense, value.settlement,
        value.accountingEvidence.let { evidence -> ExpenseProjectionEvidenceDto(evidence.committedAt, evidence.postings.map {
            ExpenseProjectionPostingDto(it.id, it.posting.kind, it.posting.component, it.posting.method, it.posting.amount, it.posting.origin,
                it.occurredAt, it.actorId, it.commandId, it.cashSessionId, it.expenseId, it.accountingVersion)
        }, evidence.accountingVersion, CompletenessStateV2(evidence.completeness), evidence.causes.map(::CompletenessCauseV2), evidence.snapshot) })
}
@RestController
@RequestMapping("/api/v2/expenses/commands")
class ExpenseCommandProjectionController(private val service: ExpenseCommandProjectionService) {
    @GetMapping("/{commandId}/projection")
    fun projection(request: HttpServletRequest, @PathVariable commandId: UUID): ResponseEntity<BaseResponse<ExpenseCommandProjectionResponse>> {
        val data = ExpenseCommandProjectionResponseTranslator.translate(service.read(request.staffSession(), commandId))
        val status = data.state.status
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(BaseResponse(status,
            request.getHeader("X-Trace-Id")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(), data,
            if (status == 200) null else "Expense evidence unavailable or not visible",
            if (status == 200) null else data.state.wire, if (status == 200) null else false))
    }
}
