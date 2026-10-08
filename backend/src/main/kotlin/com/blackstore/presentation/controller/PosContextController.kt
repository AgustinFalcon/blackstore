package com.blackstore.presentation.controller

import com.blackstore.application.pos.PosContextApplicationService
import com.blackstore.application.dto.response.BaseResponse
import com.blackstore.domain.pos.*
import com.blackstore.infrastructure.identity.staffSession
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

enum class PosContextStateV2 { Available, Unavailable, Unknown }
data class PosContextV2Response(val state: PosContextStateV2, val context: PosExecutionContext? = null)
object PosContextResponseTranslator {
    fun translate(result: PosContextResult): PosContextV2Response = when(result) {
        is PosContextResult.Available -> PosContextV2Response(PosContextStateV2.Available,result.context)
        PosContextResult.Unavailable -> PosContextV2Response(PosContextStateV2.Unavailable)
        PosContextResult.Unknown -> PosContextV2Response(PosContextStateV2.Unknown)
    }
}
@RestController
@RequestMapping("/api/v2/pos/context")
class PosContextController(private val service: PosContextApplicationService) {
    @GetMapping
    fun observe(request: HttpServletRequest): ResponseEntity<BaseResponse<PosContextV2Response>> {
        val result=service.observe(request.staffSession().staff)
        val status=if(result is PosContextResult.Available) 200 else 503
        return ResponseEntity.status(status).header("Cache-Control","no-store").body(BaseResponse(status,
            request.getHeader("X-Trace-Id")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            PosContextResponseTranslator.translate(result),if(status==200) null else "POS context unavailable",
            if(status==200) null else "POS_CONTEXT_UNAVAILABLE",if(status==200) null else false))
    }
}
