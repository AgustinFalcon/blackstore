package com.blackstore.presentation.controller

import com.blackstore.application.dto.response.BaseResponse
import com.blackstore.application.dto.sales.*
import com.blackstore.application.sales.SaleCommandApplicationService
import com.blackstore.domain.identity.AuthenticatedSessionContext
import com.blackstore.domain.sales.*
import com.blackstore.infrastructure.identity.staffSession
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v3/sales")
class SaleV3Controller(private val service: SaleCommandApplicationService) {
    @PostMapping("/reservations")
    fun reserve(request: HttpServletRequest,@RequestBody body: SaleReserveV3Request): ResponseEntity<BaseResponse<SaleAdmissionV3Response>> {
        val resolved=request.staffSession()
        val command=try { SaleV3RequestTranslator.reserve(body) } catch(_: IllegalArgumentException) {
            return respond(request,SaleCommandResult.Rejected(SaleCommandFailure.Validation),false)
        }
        return respond(request,service.execute(AuthenticatedSessionContext(resolved.staff,resolved.session.digest),command),false)
    }
    @GetMapping("/commands/{commandId}")
    fun receipt(request: HttpServletRequest,@PathVariable commandId: UUID)=respond(request,service.receipt(request.staffSession().staff,commandId,SaleAdmissionFamily.V3),true)

    private fun respond(request: HttpServletRequest,result: SaleCommandResult,query: Boolean): ResponseEntity<BaseResponse<SaleAdmissionV3Response>> {
        val status=when(result) { is SaleCommandResult.Accepted -> if(query || result.replay) 200 else 202;SaleCommandResult.NotFound -> 404;is SaleCommandResult.Rejected -> result.failure.status;else -> 503 }
        val data=when(result) {
            is SaleCommandResult.Accepted -> result.receipt.let { r -> SaleAdmissionV3Response(SaleAdmissionOutcomeV2.Accepted,SaleAdmissionReceiptV3Response(r.commandId,r.kind,r.payloadHash,r.actorId,r.cashSessionId,r.identity.clientInstanceId,r.identity.deviceId,r.identity.saleId,r.identity.operationId,r.intentId,r.outboxId,r.acceptedAt,SaleFingerprintVersion.AdmissionV2.wire)) }
            SaleCommandResult.NotFound -> SaleAdmissionV3Response(SaleAdmissionOutcomeV2.NotFound)
            SaleCommandResult.Unavailable -> SaleAdmissionV3Response(SaleAdmissionOutcomeV2.Unavailable)
            SaleCommandResult.Unknown -> SaleAdmissionV3Response(SaleAdmissionOutcomeV2.Unknown)
            is SaleCommandResult.Rejected -> SaleAdmissionV3Response(SaleAdmissionOutcomeV2.Rejected,failure=result.failure.wire,lineFailures=result.lineFailures.map { SaleLineFailureV3Response(it.variantId,it.code) })
        }
        return ResponseEntity.status(status).header("Cache-Control","no-store").body(BaseResponse(status,request.getHeader("X-Trace-Id")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),data,
            if(status<300) null else "Sale operation unavailable or denied",if(status<300) null else data.failure ?: data.outcome.name,if(status<300) null else false))
    }
}
