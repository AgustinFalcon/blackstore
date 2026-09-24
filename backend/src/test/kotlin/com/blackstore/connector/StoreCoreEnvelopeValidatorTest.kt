package com.blackstore.connector

import com.blackstore.application.dto.storecore.StoreCoreReceiptDto
import com.blackstore.application.dto.storecore.toDomain
import com.blackstore.application.storecore.StoreCoreEnvelope
import com.blackstore.application.storecore.StoreCoreEnvelopeValidator
import com.blackstore.application.storecore.StoreCoreRecoveryPolicy
import com.blackstore.domain.exception.ForbiddenOperationException
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.model.StoreCoreCanonicalContract
import com.blackstore.domain.model.StoreCoreOperationKind
import com.blackstore.domain.model.StoreCoreOperationState
import com.blackstore.domain.model.StoreCoreRecoveryAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class StoreCoreEnvelopeValidatorTest {

    private val validator = StoreCoreEnvelopeValidator()
    private val quadruple =
        OperationQuadruple(
            clientInstanceId = "ci-1",
            deviceId = "dev-1",
            saleId = "sale-1",
            operationId = "op-1",
        )

    @Test
    fun successRequiresExplicitNullErrorFields() {
        val data = "ok"
        val envelope =
            StoreCoreEnvelope(
                code = 200,
                data = data,
                errorCode = null,
                retryable = null,
                message = null,
                traceId = "t-1",
            )
        validator.requireStatusMatchesCode(200, envelope)
        assertEquals(data, validator.requireSuccess(envelope))
    }

    @Test
    fun malformedSuccessWithMessageIsRejected() {
        assertThrows<ForbiddenOperationException> {
            validator.requireSuccess(
                StoreCoreEnvelope(
                    code = 200,
                    data = "ok",
                    errorCode = null,
                    retryable = null,
                    message = "do not parse me",
                    traceId = "t-1",
                ),
            )
        }
    }

    @Test
    fun statusMismatchFailsClosed() {
        val envelope =
            StoreCoreEnvelope<String>(
                code = 409,
                data = null,
                errorCode = "CONFLICT",
                retryable = true,
                message = "ignored",
                traceId = "t-1",
            )
        assertThrows<ForbiddenOperationException> {
            validator.requireStatusMatchesCode(200, envelope)
        }
        assertEquals("CONFLICT", validator.requireError(envelope))
        assertEquals(StoreCoreRecoveryAction.GET_SAME_QUADRUPLE, StoreCoreRecoveryPolicy.actionForError("CONFLICT"))
    }

    @Test
    fun errorCodeNotMessageDrivesRecovery() {
        val a =
            validator.requireError(
                StoreCoreEnvelope<String>(
                    code = 410,
                    data = null,
                    errorCode = "OPERATION_RETIRED",
                    retryable = false,
                    message = "cualquier texto",
                    traceId = "t-1",
                ),
            )
        val b =
            validator.requireError(
                StoreCoreEnvelope<String>(
                    code = 410,
                    data = null,
                    errorCode = "OPERATION_RETIRED",
                    retryable = false,
                    message = "otro texto",
                    traceId = "t-2",
                ),
            )
        assertEquals(a, b)
        assertEquals(StoreCoreRecoveryAction.NEVER_REPOST, StoreCoreRecoveryPolicy.actionForError(a))
    }

    @Test
    fun receiptDtoMapsWithoutHttpTypes() {
        val receipt =
            StoreCoreReceiptDto(
                state = "RESERVED",
                contractVersion = StoreCoreCanonicalContract.VERSION,
                receipt = "rcpt-1",
                reservationRef = "res-1",
                acceptedPriceVersions = listOf("price-v1"),
            ).toDomain(
                quadruple,
                StoreCoreOperationKind.RESERVE,
                StoreCoreCanonicalContract.CANONICAL_PATH,
                StoreCoreCanonicalContract.SHA256,
            )
        assertEquals(StoreCoreOperationState.RESERVED, receipt.state)
        assertEquals("rcpt-1", receipt.receipt)
    }
}
