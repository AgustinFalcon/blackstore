package com.blackstore.infrastructure.persistence

import com.blackstore.domain.model.*
import com.blackstore.domain.sales.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant

/** Validates the allowlisted, checksummed terminal receipt against its durable command and reservation. */
internal class TerminalRemoteEvidenceTranslator {
    private val json = jacksonObjectMapper()
    private val mapper = DurableCommandMapper()

    /** The only wire-to-domain translator for durable terminal evidence. */
    fun translateReceipt(raw: String, hash: String): StoreCoreOperationReceipt {
        val node = json.readTree(raw)
        fun text(key: String): String = node.path(key).takeIf { it.isTextual && it.asText().isNotBlank() }?.asText()
            ?: error("incomplete terminal evidence")
        require(node.path("schemaVersion").isIntegralNumber && node.path("schemaVersion").asInt() == 1)
        require(node.path("acceptedPriceVersions").isArray)
        val identity = OperationQuadruple(text("clientInstanceId"), text("deviceId"), text("saleId"), text("operationId"))
        val kind = StoreCoreOperationKind.entries.singleOrNull { it.name == text("kind") } ?: error("unknown receipt kind")
        val state = StoreCoreOperationState.entries.singleOrNull { it.name == text("state") } ?: error("unknown receipt state")
        val contract = StoreCoreContractRef(text("canonicalPath"), text("contractVersion"), text("openapiDigest"))
        val versions = node.path("acceptedPriceVersions").map { require(it.isTextual && it.asText().isNotBlank()); it.asText() }
        val expiry = node.path("expiresAt").let { require(it.isNull || it.isTextual); if (it.isNull) null else Instant.parse(it.asText()) }
        val receipt = StoreCoreOperationReceipt(identity, kind, state, text("reservationRef"), text("receipt"), contract, versions, expiry)
        val canonical = mapper.remoteEvidence(receipt)
        require(json.readTree(canonical) == node && mapper.hash(canonical) == hash)
        return receipt
    }

    fun translate(raw: String, hash: String, command: OutboxCommand, status: SaleStatus, reservation: RemoteEvidence): RemoteEvidence {
        val expectedKind = when (status) {
            SaleStatus.COMMITTED -> StoreCoreOperationKind.COMMIT
            SaleStatus.RELEASED -> StoreCoreOperationKind.RELEASE
            else -> error("terminal sale required")
        }
        val expectedState = if (status == SaleStatus.COMMITTED) StoreCoreOperationState.COMMITTED else StoreCoreOperationState.RELEASED
        val receipt = translateReceipt(raw, hash)
        require(command.kind == expectedKind && receipt.kind == expectedKind && receipt.state == expectedState)
        val identity = receipt.quadruple
        require(identity == command.quadruple)
        val contract = receipt.contract
        require(contract.canonicalPath == command.canonicalPath && contract.contractVersion == command.contractVersion && contract.openapiDigestSha256 == command.openapiDigest)
        val reference = requireNotNull(receipt.reservationRef)
        require(reference == (command.payload as? CanonicalCommandPayload.Terminal)?.reservationRef && reference == reservation.reservationRef)
        require(contract.contractVersion == reservation.contractVersion && contract.openapiDigestSha256 == reservation.openapiDigest)
        val versions = receipt.acceptedPriceVersions
        require(versions == reservation.acceptedPriceVersions)
        return RemoteEvidence(reference, requireNotNull(receipt.receipt), contract.contractVersion, requireNotNull(contract.openapiDigestSha256), versions, receipt.expiresAt)
    }
}
