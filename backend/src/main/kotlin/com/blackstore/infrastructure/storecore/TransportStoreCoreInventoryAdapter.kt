package com.blackstore.infrastructure.storecore

import com.blackstore.application.dto.storecore.StoreCoreReceiptDto
import com.blackstore.application.dto.storecore.StoreCoreReservationRequestDto
import com.blackstore.application.dto.storecore.StoreCoreReserveLineDto
import com.blackstore.application.dto.storecore.toDomain
import com.blackstore.application.storecore.StoreCoreEnvelope
import com.blackstore.application.storecore.StoreCoreEnvelopeValidator
import com.blackstore.domain.exception.StoreCoreRemoteFault
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.model.StoreCoreCanonicalContract
import com.blackstore.domain.model.StoreCoreOperationKind
import com.blackstore.domain.model.StoreCoreOperationReceipt
import com.blackstore.domain.port.out.storecore.CommitInventoryCommand
import com.blackstore.domain.port.out.storecore.ReconcileProjection
import com.blackstore.domain.port.out.storecore.ReconcileQuery
import com.blackstore.domain.port.out.storecore.ReleaseInventoryCommand
import com.blackstore.domain.port.out.storecore.ReserveInventoryCommand
import com.blackstore.domain.port.out.storecore.StoreCoreInventoryPort
import com.blackstore.domain.port.out.storecore.StoreCoreReconcilePort
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Maps loopback HTTP to ports. Not a Spring bean; default InventoryPort stays fixture.
 */
class TransportStoreCoreInventoryAdapter(
    private val transport: StoreCoreHttpTransport,
    private val validator: StoreCoreEnvelopeValidator,
    private val mapper: ObjectMapper,
    private val path: String = StoreCoreCanonicalContract.CANONICAL_PATH,
    private val digest: String = StoreCoreCanonicalContract.SHA256,
    private val receipts: ObjectMapper = mapper.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES),
) : StoreCoreInventoryPort, StoreCoreReconcilePort {

    override fun reserve(command: ReserveInventoryCommand): StoreCoreOperationReceipt {
        val body =
            mapper.writeValueAsString(
                StoreCoreReservationRequestDto(
                    catalogVersion = command.catalogVersion,
                    lines =
                        command.lines.map {
                            StoreCoreReserveLineDto(it.variantId, it.quantity, it.expectedPriceVersion)
                        },
                ),
            )
        return parse(transport.post("$path/reservations", body, command.quadruple), command.quadruple, StoreCoreOperationKind.RESERVE)
    }

    override fun commit(command: CommitInventoryCommand): StoreCoreOperationReceipt =
        parse(
            transport.post("$path/reservations/${command.reservationRef}/commit", "{}", command.quadruple),
            command.quadruple,
            StoreCoreOperationKind.COMMIT,
        )

    override fun release(command: ReleaseInventoryCommand): StoreCoreOperationReceipt =
        parse(
            transport.post("$path/reservations/${command.reservationRef}/release", "{}", command.quadruple),
            command.quadruple,
            StoreCoreOperationKind.RELEASE,
        )

    override fun getOperation(quadruple: OperationQuadruple): StoreCoreOperationReceipt? =
        try {
            parse(transport.get(quadruple), quadruple, kindHint = null)
        } catch (ex: StoreCoreRemoteFault) {
            if (ex.errorCode == "NOT_FOUND") null else throw ex
        }

    override fun reconcile(query: ReconcileQuery): ReconcileProjection {
        require(query.clientInstanceId.isNotBlank()) { "reconcile requires X-Client-Instance-Id" }
        val body = mapper.writeValueAsString(mapOf("knownReceipts" to query.knownReceipts))
        val response = transport.postClientInstance("$path/operations/reconcile", body, query.clientInstanceId)
        val root = mapper.readTree(response.body.ifBlank { "{}" })
        val errorCode = textOrNull(root, "errorCode")
        if (errorCode != null) {
            throw StoreCoreRemoteFault(errorCode)
        }
        val data = root.path("data")
        val unknown =
            data.path("unknownReceipts").takeIf { it.isArray }?.map { it.asText() } ?: emptyList()
        val present =
            data.path("present").takeIf { it.isArray }?.mapNotNull { node ->
                if (!node.has("state")) return@mapNotNull null
                val dto = receipts.convertValue(node, StoreCoreReceiptDto::class.java)
                val remote =
                    OperationQuadruple(
                        textOrNull(node, "clientInstanceId") ?: return@mapNotNull null,
                        textOrNull(node, "deviceId") ?: return@mapNotNull null,
                        textOrNull(node, "saleId") ?: return@mapNotNull null,
                        textOrNull(node, "operationId") ?: return@mapNotNull null,
                    )
                dto.toDomain(remote, kindFromState(dto.state), path, digest)
            } ?: emptyList()
        return ReconcileProjection(present = present, unknownReceipts = unknown)
    }

    private fun parse(
        response: StoreCoreTransportResponse,
        quadruple: OperationQuadruple,
        kind: StoreCoreOperationKind? = null,
        kindHint: StoreCoreOperationKind? = kind,
    ): StoreCoreOperationReceipt {
        val root = mapper.readTree(response.body.ifBlank { "{}" })
        val errorCode = textOrNull(root, "errorCode")
        val envelope =
            StoreCoreEnvelope(
                code = root.path("code").takeIf { it.isNumber }?.asInt() ?: response.status,
                data = if (errorCode == null) mapper.convertValue(root.path("data"), StoreCoreReceiptDto::class.java) else null,
                errorCode = errorCode,
                retryable = if (root.path("retryable").isBoolean) root.path("retryable").asBoolean() else null,
                message = textOrNull(root, "message"),
                traceId = textOrNull(root, "traceId") ?: "missing-trace",
            )
        if (errorCode != null) {
            validator.requireError(envelope)
            throw StoreCoreRemoteFault(errorCode, envelope.retryable == true)
        }
        val dto = validator.requireSuccess(envelope)
        return dto.toDomain(quadruple, kindHint ?: kindFromState(dto.state), path, digest)
    }

    private fun kindFromState(state: String) =
        when (state) {
            "COMMITTED" -> StoreCoreOperationKind.COMMIT
            "RELEASED" -> StoreCoreOperationKind.RELEASE
            else -> StoreCoreOperationKind.RESERVE
        }

    private fun textOrNull(root: JsonNode, field: String): String? {
        val node = root.path(field)
        return if (node.isMissingNode || node.isNull || !node.isTextual || node.asText().isBlank()) null else node.asText()
    }
}
