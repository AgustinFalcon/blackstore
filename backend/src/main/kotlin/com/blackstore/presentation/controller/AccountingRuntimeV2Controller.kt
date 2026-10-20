package com.blackstore.presentation.controller

import com.blackstore.application.dto.response.BaseResponse
import com.blackstore.domain.port.out.accounting.*
import com.blackstore.infrastructure.identity.staffSession
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
class AccountingRuntimeV2Controller(private val ports: ObjectProvider<AccountingLifecycleQuery>) {
    @GetMapping("/api/v2/accounting/runtime")
    fun runtime(request: HttpServletRequest): ResponseEntity<BaseResponse<AccountingRuntimeV2Response>> {
        val staff=request.staffSession().staff
        val result=ports.ifAvailable?.observe(staff) ?: AccountingLifecycleResult.Unavailable
        val observation=(result as? AccountingLifecycleResult.Observed)?.observation
        val status=if(observation!=null) 200 else 503
        val data=observation?.let { AccountingRuntimeV2Response(it.state.wire,it.activationAt,it.contractVersion.name,it.observedAt) }
        return ResponseEntity.status(status).header("Cache-Control","no-store").body(BaseResponse(status,request.getHeader("X-Trace-Id")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),data,
            if(status==200) null else "Accounting runtime unavailable",if(status==200) null else "UNAVAILABLE",if(status==200) null else false))
    }
}
data class AccountingRuntimeV2Response(val state: String,val activationAt: java.time.Instant?,val contractVersion: String,val observedAt: java.time.Instant)
