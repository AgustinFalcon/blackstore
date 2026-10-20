package com.blackstore.presentation.controller

import com.blackstore.application.dto.response.BaseResponse
import com.blackstore.application.dto.sales.*
import com.blackstore.application.sales.SaleCommandApplicationService
import com.blackstore.domain.sales.*
import com.blackstore.infrastructure.identity.staffSession
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v2/sales")
class SaleV2Controller(private val service: SaleCommandApplicationService) {
    @PostMapping("/reservations") fun reserve(request: HttpServletRequest,@RequestBody body: SaleReserveV2Request)=mutate(request) { SaleV2RequestTranslator.reserve(body) }
    @PostMapping("/{operationId}/commit") fun commit(request: HttpServletRequest,@PathVariable operationId: String,@RequestBody body: SaleTerminalV2Request)=mutate(request) { SaleV2RequestTranslator.terminal(operationId,body,SaleCommandKind.Commit) }
    @PostMapping("/{operationId}/release") fun release(request: HttpServletRequest,@PathVariable operationId: String,@RequestBody body: SaleTerminalV2Request)=mutate(request) { SaleV2RequestTranslator.terminal(operationId,body,SaleCommandKind.Release) }
    @GetMapping("/commands/{commandId}") fun receipt(request: HttpServletRequest,@PathVariable commandId: UUID)=respond(request,service.receipt(request.staffSession().staff,commandId),true)
    private fun mutate(request: HttpServletRequest,translate: ()->SaleCommand): ResponseEntity<BaseResponse<SaleAdmissionV2Response>> {
        val staff=request.staffSession().staff
        val command=try { translate() } catch(_: IllegalArgumentException) { return respond(request,SaleCommandResult.Rejected(SaleCommandFailure.Validation),false) }
        return respond(request,service.execute(staff,command),false)
    }
    private fun respond(request: HttpServletRequest,result: SaleCommandResult,query: Boolean): ResponseEntity<BaseResponse<SaleAdmissionV2Response>> {
        val status=when(result) { is SaleCommandResult.Accepted -> if(query || result.replay) 200 else 202;SaleCommandResult.NotFound -> 404;is SaleCommandResult.Rejected -> result.failure.status;else -> 503 }
        val data=SaleV2ResponseTranslator.translate(result)
        return ResponseEntity.status(status).header("Cache-Control","no-store").body(BaseResponse(status,request.getHeader("X-Trace-Id")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),data,
            if(status<300) null else "Sale operation unavailable or denied",if(status<300) null else data.failure ?: data.outcome.name,if(status<300) null else false))
    }
}
