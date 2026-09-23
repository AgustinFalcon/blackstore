package com.blackstore.application.storecore

import com.blackstore.domain.exception.ForbiddenOperationException
import org.springframework.stereotype.Component

data class StoreCoreEnvelope<T>(
    val code: Int,
    val data: T?,
    val errorCode: String?,
    val retryable: Boolean?,
    val message: String?,
    val traceId: String?,
)

@Component
class StoreCoreEnvelopeValidator {

    fun <T> requireSuccess(envelope: StoreCoreEnvelope<T>): T {
        requireComplete(envelope)
        if (envelope.code != 200 || envelope.data == null || envelope.errorCode != null || envelope.retryable != null || envelope.message != null) {
            throw ForbiddenOperationException("success envelope must be code 200 with data and explicit null error fields")
        }
        return envelope.data
    }

    fun <T> requireError(envelope: StoreCoreEnvelope<T>): String {
        requireComplete(envelope)
        if (envelope.data != null || envelope.errorCode.isNullOrBlank() || envelope.retryable == null || envelope.message.isNullOrBlank()) {
            throw ForbiddenOperationException("error envelope requires data null and non-null errorCode, retryable and message")
        }
        if (envelope.errorCode == "OPERATION_RETIRED" && envelope.retryable != false) {
            throw ForbiddenOperationException("OPERATION_RETIRED must be retryable=false")
        }
        return envelope.errorCode
    }

    private fun <T> requireComplete(envelope: StoreCoreEnvelope<T>) {
        if (envelope.traceId.isNullOrBlank()) {
            throw ForbiddenOperationException("envelope requires traceId")
        }
    }
}
