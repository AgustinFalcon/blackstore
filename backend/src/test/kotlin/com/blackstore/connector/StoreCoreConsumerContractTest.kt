package com.blackstore.connector

import com.blackstore.application.storecore.StoreCoreEnvelopeValidator
import com.blackstore.application.storecore.StoreCoreRecoveryPolicy
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.model.StoreCoreCanonicalContract
import com.blackstore.domain.model.StoreCoreOperationKind
import com.blackstore.domain.model.StoreCoreRecoveryAction
import com.blackstore.application.dto.storecore.toDomain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path

class StoreCoreConsumerContractTest {

    private val validator = StoreCoreEnvelopeValidator()
    private val quadruple = OperationQuadruple("ci-1", "dev-1", "sale-1", "op-1")

    @Test
    fun fixturesPinCurrentDigestAndRejectDrift() {
        val pin = StoreCoreContractFixtures.pin()
        assertEquals(StoreCoreCanonicalContract.CANONICAL_PATH, pin.path("canonicalPath").asText())
        assertEquals(StoreCoreCanonicalContract.VERSION, pin.path("version").asText())
        assertEquals(StoreCoreCanonicalContract.SHA256, pin.path("sha256").asText())
        assertEquals(30, pin.path("rates").path("reserveRps").asInt())
        StoreCoreCanonicalContract.assertCompatible(
            pin.path("canonicalPath").asText(),
            pin.path("version").asText(),
            pin.path("sha256").asText(),
        )
        assertThrows<IllegalStateException> {
            StoreCoreCanonicalContract.assertCompatible(
                pin.path("canonicalPath").asText(),
                pin.path("version").asText(),
                pin.path("supersededSha256").asText(),
            )
        }
        assertFalse(Files.exists(Path.of("src/test/resources/blackstore-integration.openapi.yaml")))
    }

    @Test
    fun consumerMapsPinnedEnvelopesToRecoveryActions() {
        val reserved = validator.requireSuccess(StoreCoreContractFixtures.envelope("reserved"))
        val reservedDomain = reserved!!.toDomain(quadruple, StoreCoreOperationKind.RESERVE, StoreCoreCanonicalContract.CANONICAL_PATH, StoreCoreCanonicalContract.SHA256)
        assertEquals(StoreCoreRecoveryAction.RETAIN_DURABLE_EVIDENCE, StoreCoreRecoveryPolicy.actionFor(reservedDomain))

        val pending = validator.requireSuccess(StoreCoreContractFixtures.envelope("pending"))
        val pendingDomain = pending!!.toDomain(quadruple, StoreCoreOperationKind.RESERVE, StoreCoreCanonicalContract.CANONICAL_PATH, null)
        assertEquals(StoreCoreRecoveryAction.RETAIN_PENDING, StoreCoreRecoveryPolicy.actionFor(pendingDomain))
        assertNull(pendingDomain.receipt)

        val expired = validator.requireSuccess(StoreCoreContractFixtures.envelope("expired"))
        val expiredDomain = expired!!.toDomain(quadruple, StoreCoreOperationKind.RESERVE, StoreCoreCanonicalContract.CANONICAL_PATH, StoreCoreCanonicalContract.SHA256)
        assertEquals(StoreCoreRecoveryAction.RECORD_RECONCILIATION_REQUIRED, StoreCoreRecoveryPolicy.actionFor(expiredDomain))

        assertEquals("NOT_FOUND", validator.requireError(StoreCoreContractFixtures.envelope("not-found")))
        assertEquals(StoreCoreRecoveryAction.NEW_OPERATION_SAME_SALE, StoreCoreRecoveryPolicy.actionForError("NOT_FOUND"))
        assertEquals(StoreCoreRecoveryAction.GET_SAME_QUADRUPLE, StoreCoreRecoveryPolicy.actionForError(validator.requireError(StoreCoreContractFixtures.envelope("conflict"))))
        assertEquals(StoreCoreRecoveryAction.RETAIN_DURABLE_EVIDENCE, StoreCoreRecoveryPolicy.actionForError(validator.requireError(StoreCoreContractFixtures.envelope("mismatch"))))
        assertEquals(StoreCoreRecoveryAction.NEVER_REPOST, StoreCoreRecoveryPolicy.actionForError(validator.requireError(StoreCoreContractFixtures.envelope("retired"))))
    }
}
