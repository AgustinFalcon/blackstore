package com.blackstore.infrastructure.exception

import com.blackstore.application.dto.response.BaseResponse
import com.blackstore.application.dto.response.HttpCode
import com.blackstore.domain.exception.BlockedStoreCoreIntegrationException
import com.blackstore.domain.exception.ForbiddenOperationException
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(BlockedStoreCoreIntegrationException::class)
    fun handleBlockedIntegration(
        ex: BlockedStoreCoreIntegrationException,
        request: HttpServletRequest,
    ): ResponseEntity<BaseResponse<Nothing>> {
        log.warn("Blocked StoreCore integration attempt: {}", request.requestURI)
        val traceId = traceId(request)
        val body =
            BaseResponse.error<Nothing>(
                httpCode = HttpCode.SERVICE_UNAVAILABLE,
                traceId = traceId,
                errorCode = "INTEGRATION_BLOCKED",
                message = ex.message ?: "StoreCore integration is not authorized",
                retryable = false,
            )
        return ResponseEntity.status(HttpCode.SERVICE_UNAVAILABLE.code).body(body)
    }

    @ExceptionHandler(ForbiddenOperationException::class)
    fun handleForbidden(
        ex: ForbiddenOperationException,
        request: HttpServletRequest,
    ): ResponseEntity<BaseResponse<Nothing>> {
        val body =
            BaseResponse.error<Nothing>(
                httpCode = HttpCode.FORBIDDEN,
                traceId = traceId(request),
                errorCode = "FORBIDDEN",
                message = ex.message ?: "operation is not allowed",
                retryable = false,
            )
        return ResponseEntity.status(HttpCode.FORBIDDEN.code).body(body)
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(
        ex: IllegalArgumentException,
        request: HttpServletRequest,
    ): ResponseEntity<BaseResponse<Nothing>> {
        val body =
            BaseResponse.error<Nothing>(
                httpCode = HttpCode.BAD_REQUEST,
                traceId = traceId(request),
                errorCode = "VALIDATION",
                message = ex.message ?: "Invalid request",
                retryable = false,
            )
        return ResponseEntity.badRequest().body(body)
    }

    private fun traceId(request: HttpServletRequest): String =
        request.getHeader(TRACE_HEADER)?.takeIf { it.isNotBlank() }
            ?: java.util.UUID.randomUUID().toString()

    companion object {
        const val TRACE_HEADER = "X-Trace-Id"
    }
}
