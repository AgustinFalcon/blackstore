package com.blackstore.presentation.controller

import com.blackstore.application.dto.response.BaseResponse
import com.blackstore.application.dto.response.HealthResponse
import com.blackstore.application.service.HealthApplicationService
import com.blackstore.infrastructure.exception.GlobalExceptionHandler
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/health")
class HealthController(
    private val healthApplicationService: HealthApplicationService,
) {
    @GetMapping
    fun health(request: HttpServletRequest): ResponseEntity<BaseResponse<HealthResponse>> {
        val traceId =
            request.getHeader(GlobalExceptionHandler.TRACE_HEADER)?.takeIf { it.isNotBlank() }
                ?: java.util.UUID.randomUUID().toString()
        return ResponseEntity.ok(BaseResponse.success(healthApplicationService.health(), traceId))
    }
}
