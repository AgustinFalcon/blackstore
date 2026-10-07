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

    @ExceptionHandler(com.blackstore.domain.cash.CashMutationException::class)
    fun handleCashMutation(ex: com.blackstore.domain.cash.CashMutationException, request: HttpServletRequest): ResponseEntity<BaseResponse<Nothing>> =
        ResponseEntity.status(ex.failure.status).header("Cache-Control", "no-store").body(
            BaseResponse.error(HttpCode.entries.first { it.code == ex.failure.status }, traceId(request), ex.failure.code, ex.failure.label, false))

    @ExceptionHandler(com.blackstore.domain.identity.StaffSecurityException::class)
    fun handleStaffSecurity(ex: com.blackstore.domain.identity.StaffSecurityException, request: HttpServletRequest): ResponseEntity<BaseResponse<Nothing>> =
        ResponseEntity.status(ex.status).header("Cache-Control", "no-store").body(BaseResponse.error(HttpCode.entries.first { it.code == ex.status }, traceId(request), ex.errorCode, "Staff operation denied", false))

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException::class, org.springframework.web.bind.MethodArgumentNotValidException::class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException::class)
    fun handleInvalidBody(request: HttpServletRequest): ResponseEntity<BaseResponse<Nothing>> =
        ResponseEntity.badRequest().header("Cache-Control", "no-store").body(BaseResponse.error(HttpCode.BAD_REQUEST, traceId(request), "VALIDATION", "Invalid request", false))

    @ExceptionHandler(java.sql.SQLException::class)
    fun handlePersistenceUnavailable(request: HttpServletRequest): ResponseEntity<BaseResponse<Nothing>> =
        ResponseEntity.status(503).header("Cache-Control", "no-store").body(BaseResponse.error(HttpCode.SERVICE_UNAVAILABLE, traceId(request), "PERSISTENCE_UNAVAILABLE", "Service unavailable", false))

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(request: HttpServletRequest): ResponseEntity<BaseResponse<Nothing>> =
        ResponseEntity.status(500).header("Cache-Control", "no-store").body(BaseResponse.error(HttpCode.INTERNAL_SERVER_ERROR, traceId(request), "INTERNAL_ERROR", "Operation failed", false))

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
