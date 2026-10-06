package com.blackstore.infrastructure.persistence

import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.model.StoreCoreOperationKind
import com.blackstore.domain.sales.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.security.MessageDigest

/** The single JSON/JDBC boundary. Only allowlisted dispatch fields are serialized. */
class DurableCommandMapper {
 private val json = jacksonObjectMapper()
 fun kind(wire: String?): CommandKind = CommandKind.entries.firstOrNull { it.name == wire } ?: CommandKind.UNKNOWN
 fun delivery(wire: String?): DeliveryState = DeliveryState.entries.firstOrNull { it.name == wire } ?: DeliveryState.UNKNOWN
 fun state(wire: String?): DurableSaleState = DurableSaleState.entries.firstOrNull { it.name == wire } ?: DurableSaleState.UNKNOWN
 fun encode(command: OutboxCommand): String {
  val payload = requireNotNull(command.payload) { "legacy command is not replayable" }
  val node = json.createObjectNode().put("schemaVersion",1).put("kind",command.kind.name)
   .put("clientInstanceId",command.quadruple.clientInstanceId).put("deviceId",command.quadruple.deviceId)
   .put("saleId",command.quadruple.saleId).put("operationId",command.quadruple.operationId)
   .put("canonicalPath",command.canonicalPath).put("contractVersion",command.contractVersion).put("openapiDigest",command.openapiDigest)
  when(payload) {
   is CanonicalCommandPayload.Reserve -> {
    require(command.kind == StoreCoreOperationKind.RESERVE)
    node.put("catalogVersion",payload.catalogVersion)
    val lines = node.putArray("lines")
    payload.lines.forEach { lines.addObject().put("variantId",it.variantId).put("quantity",it.quantity).put("expectedPriceVersion",it.expectedPriceVersion) }
   }
   is CanonicalCommandPayload.Terminal -> {
    require(command.kind == StoreCoreOperationKind.COMMIT || command.kind == StoreCoreOperationKind.RELEASE)
    node.put("reservationRef",payload.reservationRef)
   }
  }
  return json.writeValueAsString(node)
 }
 fun decode(raw: String, expectedHash: String, requestHash: String): OutboxCommand {
  val node = json.readTree(raw)
  require(node.path("schemaVersion").asInt() == 1)
  fun text(key: String): String = node.path(key).takeIf { it.isTextual && it.asText().isNotBlank() }?.asText() ?: error("incomplete canonical payload")
  val kind = when(kind(text("kind"))) { CommandKind.RESERVE -> StoreCoreOperationKind.RESERVE; CommandKind.COMMIT -> StoreCoreOperationKind.COMMIT; CommandKind.RELEASE -> StoreCoreOperationKind.RELEASE; else -> error("unknown command") }
  val payload = if(kind == StoreCoreOperationKind.RESERVE) CanonicalCommandPayload.Reserve(text("catalogVersion"), node.path("lines").map {
   CanonicalReserveLine(it.path("variantId").asText(),it.path("quantity").asInt(),it.path("expectedPriceVersion").asText())
  }) else CanonicalCommandPayload.Terminal(text("reservationRef"))
  val command = OutboxCommand(OperationQuadruple(text("clientInstanceId"),text("deviceId"),text("saleId"),text("operationId")),kind,text("canonicalPath"),text("contractVersion"),text("openapiDigest"),requestHash,(payload as? CanonicalCommandPayload.Terminal)?.reservationRef,payload)
  require(hash(encode(command)) == expectedHash) { "canonical payload checksum mismatch" }
  require(json.readTree(encode(command)) == node) { "non-allowlisted canonical payload" }
  return command
 }
 fun evidence(saga: SaleSaga, remoteState: String): String {
  val node = json.createObjectNode().put("schemaVersion",1).put("state",remoteState)
  saga.evidence?.let {
   node.put("reservationRef",it.reservationRef).put("receipt",it.receipt).put("contractVersion",it.contractVersion).put("openapiDigest",it.openapiDigest)
   it.expiresAt?.let { expiry -> node.put("expiresAt",expiry.toString()) }
   val versions = node.putArray("acceptedPriceVersions"); it.acceptedPriceVersions.forEach(versions::add)
  }
  saga.reconciliationReason?.let { node.put("reconciliationReason",it) }
  return json.writeValueAsString(node)
 }
 fun hash(raw: String): String = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
