package com.blackstore.infrastructure.persistence

import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.model.StoreCoreOperationKind
import com.blackstore.domain.sales.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class DurableCommandMapperTest {
 private val mapper=DurableCommandMapper()
 private val identity=OperationQuadruple(UUID.randomUUID().toString(),"device","sale",UUID.randomUUID().toString())
 @Test fun canonicalRoundTripPreservesExactDispatchForEveryKind() {
  StoreCoreOperationKind.entries.filter { it in setOf(StoreCoreOperationKind.RESERVE,StoreCoreOperationKind.COMMIT,StoreCoreOperationKind.RELEASE) }.forEach { kind ->
   val payload=if(kind==StoreCoreOperationKind.RESERVE) CanonicalCommandPayload.Reserve("catalog-old",listOf(CanonicalReserveLine("variant-old",2,"price-old"))) else CanonicalCommandPayload.Terminal("reservation-old")
   val command=OutboxCommand(identity,kind,"/blackstore-integration/v1","v1","a".repeat(64),"b".repeat(64),(payload as? CanonicalCommandPayload.Terminal)?.reservationRef,payload)
   val encoded=mapper.encode(command)
   assertEquals(command,mapper.decode(encoded,mapper.hash(encoded),command.requestHash))
   assertFalse(encoded.contains("actor"));assertFalse(encoded.contains("cash"));assertFalse(encoded.contains("product"))
   assertThrows<IllegalArgumentException> {mapper.decode(encoded,"c".repeat(64),command.requestHash)}
  }
 }
 @Test fun unknownAndLegacyFailClosed() {
  assertEquals(CommandKind.UNKNOWN,mapper.kind("new-kind"));assertEquals(DeliveryState.UNKNOWN,mapper.delivery(null));assertEquals(DurableSaleState.UNKNOWN,mapper.state("future"))
  assertThrows<IllegalArgumentException> {mapper.encode(OutboxCommand(identity,StoreCoreOperationKind.RESERVE,"path","v","a".repeat(64),"b".repeat(64)))}
 }
 @Test fun canonicalPayloadRejectsSecretsAndUnversionedJson() {
  val command=OutboxCommand(identity,StoreCoreOperationKind.COMMIT,"path","v","a".repeat(64),"b".repeat(64),"ref",CanonicalCommandPayload.Terminal("ref"))
  val encoded=mapper.encode(command);val injected=encoded.dropLast(1)+",\"token\":\"secret\"}"
  assertThrows<IllegalArgumentException> { mapper.decode(injected,mapper.hash(encoded),command.requestHash) }
 }
 @Test fun codecV2RoundTripsUnicodeWhitespaceWithoutReinterpretingV1() {
  val value="\u00A0";val unicode=identity.copy(deviceId=value,saleId=value)
  val payload=CanonicalCommandPayload.Reserve("catalog",listOf(CanonicalReserveLine(value,1,value)))
  val v1=OutboxCommand(unicode,StoreCoreOperationKind.RESERVE,"path","v","a".repeat(64),"b".repeat(64),payload=payload)
  val encodedV1=mapper.encode(v1);assertThrows<IllegalArgumentException> { mapper.decode(encodedV1,mapper.hash(encodedV1),v1.requestHash) }
  val v2=v1.copy(codecVersion=DurableCommandCodecVersion.V2);val encodedV2=mapper.encode(v2)
  assertEquals(v2,mapper.decode(encodedV2,mapper.hash(encodedV2),v2.requestHash))
  assertEquals(DurableCommandCodecVersion.V2,DurableCommandCodecVersion.fromWire(2));assertEquals(DurableCommandCodecVersion.Unknown,DurableCommandCodecVersion.fromWire(3))
 }
}
