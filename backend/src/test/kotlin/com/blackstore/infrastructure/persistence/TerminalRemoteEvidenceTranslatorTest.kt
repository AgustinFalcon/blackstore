package com.blackstore.infrastructure.persistence

import com.blackstore.domain.model.*
import com.blackstore.domain.sales.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerminalRemoteEvidenceTranslatorTest {
    private val identity = OperationQuadruple("client", "device", "sale", "operation")
    private val reservation = RemoteEvidence("reservation", "reservation-receipt", "v1", "digest", listOf("price-v1"), null)
    private val mapper = DurableCommandMapper()
    private val json = jacksonObjectMapper()
    private val translator = TerminalRemoteEvidenceTranslator()
    private fun command(kind: StoreCoreOperationKind) = OutboxCommand(identity, kind, "/integration", "v1", "digest", "request", reservationRef = "reservation", payload = CanonicalCommandPayload.Terminal("reservation"))
    private fun receipt(kind: StoreCoreOperationKind) = StoreCoreOperationReceipt(identity, kind,
        if (kind == StoreCoreOperationKind.COMMIT) StoreCoreOperationState.COMMITTED else StoreCoreOperationState.RELEASED,
        "reservation", "terminal-receipt", StoreCoreContractRef("/integration", "v1", "digest"), listOf("price-v1"), null)

    @Test fun `terminal receipts survive JSONB formatting and replace reservation receipt`() {
        for ((kind, status) in listOf(StoreCoreOperationKind.COMMIT to SaleStatus.COMMITTED, StoreCoreOperationKind.RELEASE to SaleStatus.RELEASED)) {
            val raw = mapper.remoteEvidence(receipt(kind))
            val evidence = translator.translate(json.readTree(raw).toPrettyString(), mapper.hash(raw), command(kind), status, reservation)
            assertEquals("terminal-receipt", evidence.receipt)
            assertNotEquals(reservation.receipt, evidence.receipt)
            assertEquals(reservation.acceptedPriceVersions, evidence.acceptedPriceVersions)
        }
    }

    @Test fun `unknown extra contradictory and tampered evidence fails closed`() {
        val raw = mapper.remoteEvidence(receipt(StoreCoreOperationKind.RELEASE))
        val hash = mapper.hash(raw)
        for ((key, value) in listOf("kind" to "COMMIT", "state" to "COMMITTED", "clientInstanceId" to "foreign", "canonicalPath" to "/other", "openapiDigest" to "other", "reservationRef" to "other", "receipt" to "other", "extra" to "unsafe", "kind" to "UNKNOWN")) {
            val changed = json.readTree(raw).deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().put(key, value).toString()
            assertThrows(Exception::class.java) { translator.translate(changed, hash, command(StoreCoreOperationKind.RELEASE), SaleStatus.RELEASED, reservation) }
        }
        assertThrows(Exception::class.java) { translator.translate(raw, "bad-hash", command(StoreCoreOperationKind.RELEASE), SaleStatus.RELEASED, reservation) }
        assertThrows(Exception::class.java) { translator.translate(raw, hash, command(StoreCoreOperationKind.RELEASE), SaleStatus.RELEASED, reservation.copy(acceptedPriceVersions = listOf("other"))) }
        val extra = json.readTree(raw).deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().put("extra", "unsafe").toString()
        assertThrows(Exception::class.java) { translator.translate(extra, mapper.hash(extra), command(StoreCoreOperationKind.RELEASE), SaleStatus.RELEASED, reservation) }
    }
}
