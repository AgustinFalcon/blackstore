package com.blackstore.application.dto.response

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * AssistTime-style envelope extended for StoreCore integration error discrimination.
 * HTTP status equals [code]. Success responses set explicit null error fields.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class BaseResponse<T>(
    val code: Int,
    val traceId: String,
    val data: T? = null,
    val message: String? = null,
    val errorCode: String? = null,
    val retryable: Boolean? = null,
) {
    companion object {
        fun <T> success(data: T, traceId: String): BaseResponse<T> =
            BaseResponse(
                code = HttpCode.SUCCESS.code,
                traceId = traceId,
                data = data,
                message = null,
                errorCode = null,
                retryable = null,
            )

        fun <T> error(
            httpCode: HttpCode,
            traceId: String,
            errorCode: String,
            message: String,
            retryable: Boolean,
        ): BaseResponse<T> =
            BaseResponse(
                code = httpCode.code,
                traceId = traceId,
                data = null,
                message = message,
                errorCode = errorCode,
                retryable = retryable,
            )
    }
}
