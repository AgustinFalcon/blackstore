package com.blackstore.infrastructure.persistence

import com.blackstore.domain.port.out.sales.DurableSaleStore
import com.blackstore.domain.sales.*
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.model.StoreCoreOperationReceipt
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.sql.Connection
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

class JdbcDurableSaleRepository(private val dataSource: DataSource) : DurableSaleStore {
 private val mapper = DurableCommandMapper()
 private val json = jacksonObjectMapper()
 private val writer = JdbcBlackStoreWriter()
 internal fun <T> transaction(block: (Connection) -> T): T = dataSource.connection.use { c ->
  c.autoCommit=false
  try { c.createStatement().execute("SET LOCAL ROLE blackstore_app"); val result=block(c); c.commit(); result }
  catch(e: Exception) { c.rollback(); throw e }
 }
 override fun findDurable(operationId: String): StoredSale? = transaction { c ->
  c.prepareStatement("SELECT id FROM sale_state_projection WHERE operation_id=?").use { s ->
   s.setObject(1,UUID.fromString(operationId)); s.executeQuery().use { r ->
    if(!r.next()) null else { val id=r.getLong(1); check(!r.next()) { "ambiguous operation" }; read(c,id) }
   }
  }
 }
 override fun listDurable(afterId: Long, limit: Int): List<StoredSale> = transaction { c ->
  require(afterId>=0 && limit in 1..100)
  c.prepareStatement("SELECT id FROM sale_state_projection WHERE id>? ORDER BY id LIMIT ?").use { s ->
   s.setLong(1,afterId); s.setInt(2,limit); s.executeQuery().use { r -> buildList { while(r.next()) add(read(c,r.getLong(1))) } }
  }
 }
 internal fun lock(c: Connection, operationId: String): StoredSale = c.prepareStatement("SELECT id FROM sale_state_projection WHERE operation_id=? FOR UPDATE").use { s ->
  s.setObject(1,UUID.fromString(operationId)); s.executeQuery().use { r -> check(r.next()); val id=r.getLong(1); check(!r.next()); read(c,id) }
 }
 internal fun lock(c: Connection,identity: OperationQuadruple): StoredSale = c.prepareStatement("SELECT id FROM sale_state_projection WHERE operation_id=? AND client_instance_id=? AND device_id=? AND sale_id=? FOR UPDATE").use { s ->
  s.setObject(1,UUID.fromString(identity.operationId));s.setObject(2,UUID.fromString(identity.clientInstanceId));s.setString(3,identity.deviceId);s.setString(4,identity.saleId)
  s.executeQuery().use { r ->require(r.next()) { "sale identity missing" };val id=r.getLong(1);check(!r.next());read(c,id) }
 }
 internal fun read(c: Connection,id: Long): StoredSale = c.prepareStatement("SELECT p.*,i.cash_session_id,i.created_by FROM sale_state_projection p JOIN sale_intents i ON i.id=p.sale_intent_id WHERE p.id=?").use { s ->
  s.setLong(1,id); s.executeQuery().use { r ->
   check(r.next()); val identity=OperationQuadruple(r.getString("client_instance_id"),r.getString("device_id"),r.getString("sale_id"),r.getString("operation_id"))
   val commands=mutableListOf<OutboxCommand>(); var legacy=false
   c.prepareStatement("SELECT o.*,d.state AS delivery_state FROM storecore_outbox_commands o LEFT JOIN storecore_command_delivery d ON d.command_id=o.id WHERE o.client_instance_id=? AND o.device_id=? AND o.sale_id=? AND o.operation_id=? ORDER BY o.id").use { q ->
    q.setObject(1,UUID.fromString(identity.clientInstanceId));q.setString(2,identity.deviceId);q.setString(3,identity.saleId);q.setObject(4,UUID.fromString(identity.operationId))
    q.executeQuery().use { rows -> while(rows.next()) {
     val payload=rows.getString("canonical_payload")
     val command=payload?.let { runCatching { mapper.decode(it,rows.getString("payload_hash"),rows.getString("request_hash")) }.getOrNull() }
     if(command==null || mapper.delivery(rows.getString("delivery_state")) in setOf(DeliveryState.LEGACY_INCOMPLETE,DeliveryState.UNKNOWN)) legacy=true
     if(command!=null) commands+=command
    } }
   }
   val lines=c.prepareStatement("SELECT * FROM sale_lines WHERE sale_id=? ORDER BY id").use { q ->
    q.setLong(1,id);q.executeQuery().use { rows -> buildList { while(rows.next()) add(TicketLine(rows.getString("sku"),json.readTree(rows.getString("product_snapshot")).path("name").asText(),rows.getInt("quantity"),rows.getBigDecimal("original_unit_price"),rows.getBigDecimal("discount_amount"))) } }
   }
   val evidence=r.getString("storecore_reservation_ref")?.let { ref -> runCatching { RemoteEvidence(ref,r.getString("reservation_receipt"),r.getString("contract_version"),r.getString("openapi_digest"),json.readTree(r.getString("accepted_price_versions")).map { it.asText() },r.getTimestamp("reservation_expires_at")?.toInstant()) }.getOrNull() }
   val mapped=mapper.state(r.getString("status")); val state=if(legacy || commands.isEmpty()) DurableSaleState.LEGACY_INCOMPLETE else mapped
   val status=if(state==DurableSaleState.LEGACY_INCOMPLETE || state==DurableSaleState.UNKNOWN || (mapped.name in SaleSaga.SUCCESS_STATES.map { it.name } && evidence==null)) SaleStatus.UNKNOWN else SaleStatus.fromWire(mapped.name)
   StoredSale(SaleSaga(identity,r.getLong("cash_session_id"),status,evidence,r.getString("reconciliation_reason"),commands,
    grossSales=lines.fold(java.math.BigDecimal.ZERO) { a,l -> a+l.originalUnitPrice.multiply(l.quantity.toBigDecimal()) },
    discounts=lines.fold(java.math.BigDecimal.ZERO) { a,l -> a+l.discountAmount.multiply(l.quantity.toBigDecimal()) },lines=lines,createdBy=r.getLong("created_by"),
    recoverWithGet=commands.isNotEmpty() && status in setOf(SaleStatus.COMMIT_PENDING,SaleStatus.RELEASE_PENDING),blockSameOperationRepost=state in setOf(DurableSaleState.LEGACY_INCOMPLETE,DurableSaleState.UNKNOWN)),r.getLong("version"),state,id)
  }
 }
 override fun claimNext(lease: Duration): ClaimedSaleCommand? = claim(null,null,lease)
 override fun claimCommand(operationId: String,kind: CommandKind,lease: Duration): ClaimedSaleCommand? = claim(operationId,kind,lease)
 private fun claim(operationId: String?,kind: CommandKind?,lease: Duration): ClaimedSaleCommand? = transaction { c ->
  require(lease.seconds in 1..300 && kind!=CommandKind.UNKNOWN)
  val filter=if(operationId==null) "" else " AND o.operation_id=? AND o.operation_kind=?"
  var claimed: ClaimedSaleCommand? = null
  var poisoned: Boolean
  do {
  poisoned=false
  c.prepareStatement("SELECT o.*,d.claim_epoch,d.attempts,d.state AS delivery_state FROM storecore_command_delivery d JOIN storecore_outbox_commands o ON o.id=d.command_id WHERE ((d.state IN ('PENDING','UNCERTAIN') AND d.next_attempt_at<=now()) OR (d.state='IN_FLIGHT' AND d.lease_until<=now()))$filter ORDER BY o.id FOR UPDATE OF d SKIP LOCKED LIMIT 1").use { s ->
   if(operationId!=null) {s.setObject(1,UUID.fromString(operationId));s.setString(2,kind!!.name)}
   s.executeQuery().use { r -> if(!r.next()) null else {
    val id=r.getLong("id")
    // Invalid/unknown records cannot reach HTTP or repeatedly monopolize the head of the queue.
    val command=runCatching { mapper.decode(r.getString("canonical_payload"),r.getString("payload_hash"),r.getString("request_hash")) }.getOrNull()
    if(command==null) {
     c.prepareStatement("UPDATE storecore_command_delivery SET state='LEGACY_INCOMPLETE',last_error_code='INCOMPLETE_EVIDENCE',lease_until=NULL,updated_at=now() WHERE command_id=?").use { u ->u.setLong(1,id);check(u.executeUpdate()==1) }
     poisoned=true
     null
    } else {
    val token=UUID.randomUUID();val epoch=r.getLong("claim_epoch")+1;val attempts=r.getInt("attempts")+1
    c.prepareStatement("UPDATE storecore_command_delivery SET state='IN_FLIGHT',claim_token=?,claim_epoch=?,lease_until=now()+(? * interval '1 second'),attempts=?,updated_at=now() WHERE command_id=?").use { u -> u.setObject(1,token);u.setLong(2,epoch);u.setLong(3,lease.seconds);u.setInt(4,attempts);u.setLong(5,id);check(u.executeUpdate()==1) }
    claimed=ClaimedSaleCommand(id,command,token,epoch,mapper.delivery(r.getString("delivery_state"))!=DeliveryState.PENDING,attempts,r.getLong("actor_id"),r.getLong("cash_session_id"))
    claimed
    }
   } }
  }
  } while(poisoned)
  claimed
 }
 override fun reconcileClaimEvidence(claim: ClaimedSaleCommand,receipt: StoreCoreOperationReceipt,reason: RecoveryReason): AttemptOutcome = transaction { c ->
  val stored=lock(c,claim.command.quadruple.operationId)
  val current=c.prepareStatement("SELECT state,claim_token,claim_epoch,lease_until>now() AS active FROM storecore_command_delivery WHERE command_id=? FOR UPDATE").use { s ->s.setLong(1,claim.id);s.executeQuery().use { r ->check(r.next());r.getString(1)==DeliveryState.IN_FLIGHT.name && r.getObject(2)==claim.claimToken && r.getLong(3)==claim.claimEpoch && r.getBoolean(4) } }
  // Use an explicit allowlist even for incompatible responses. Never copy an envelope, bearer or request headers.
  val evidence=mapper.remoteEvidence(receipt)
  val hash=mapper.hash(evidence)
  val identity=claim.command.quadruple
  val inbox=c.prepareStatement("INSERT INTO storecore_inbox_events(client_instance_id,device_id,sale_id,operation_id,operation_kind,state,receipt,reservation_ref,response_hash,contract_version,openapi_digest,payload_redacted,evidence_json,evidence_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?,'{}',?::jsonb,?) ON CONFLICT(client_instance_id,device_id,sale_id,operation_id,operation_kind,response_hash) DO NOTHING RETURNING id").use { s ->
   s.setObject(1,UUID.fromString(identity.clientInstanceId));s.setString(2,identity.deviceId);s.setString(3,identity.saleId);s.setObject(4,UUID.fromString(identity.operationId));s.setString(5,claim.command.kind.name);s.setString(6,receipt.state.name)
   s.setString(7,receipt.receipt);s.setObject(8,receipt.reservationRef?.let { ref -> runCatching {UUID.fromString(ref)}.getOrElse { UUID.nameUUIDFromBytes(ref.toByteArray()) } });s.setString(9,hash)
   s.setString(10,claim.command.contractVersion);s.setString(11,claim.command.openapiDigest);s.setString(12,evidence);s.setString(13,hash)
   s.executeQuery().use { r ->if(r.next()) r.getLong(1) else c.prepareStatement("SELECT id FROM storecore_inbox_events WHERE client_instance_id=? AND device_id=? AND sale_id=? AND operation_id=? AND operation_kind=? AND response_hash=? AND evidence_hash=?").use { q ->q.setObject(1,UUID.fromString(identity.clientInstanceId));q.setString(2,identity.deviceId);q.setString(3,identity.saleId);q.setObject(4,UUID.fromString(identity.operationId));q.setString(5,claim.command.kind.name);q.setString(6,hash);q.setString(7,hash);q.executeQuery().use { rows ->check(rows.next());rows.getLong(1) } } }
  }
  c.prepareStatement("INSERT INTO storecore_inbox_applications(inbox_id,command_id,claim_token,state,late_reason) VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING").use { s ->s.setLong(1,inbox);s.setLong(2,claim.id);s.setObject(3,claim.claimToken);s.setString(4,if(current) "APPLIED" else "LATE_IGNORED");s.setString(5,if(current) null else RecoveryReason.LEASE_EXPIRED.name);s.executeUpdate() }
  if(!current) AttemptOutcome.LATE_IGNORED else {
   c.prepareStatement("UPDATE sale_state_projection SET status='RECONCILIATION_REQUIRED',reconciliation_reason=?,version=version+1,updated_at=now() WHERE id=? AND version=? AND status IN ('PENDING_RESERVATION','RESERVED','PAYMENT_CAPTURED','COMMIT_PENDING','RELEASE_PENDING')").use { s ->s.setString(1,reason.name);s.setLong(2,stored.projectionId);s.setLong(3,stored.version);check(s.executeUpdate()==1) }
   c.prepareStatement("UPDATE storecore_command_delivery SET state='RECONCILIATION_REQUIRED',last_error_code=?,lease_until=NULL,updated_at=now() WHERE command_id=? AND claim_token=? AND claim_epoch=? AND state='IN_FLIGHT'").use { s ->s.setString(1,reason.name);s.setLong(2,claim.id);s.setObject(3,claim.claimToken);s.setLong(4,claim.claimEpoch);check(s.executeUpdate()==1) }
   c.prepareStatement("INSERT INTO outbox_delivery_attempts(command_id,attempt_no,state,started_at,completed_at,error_redacted) VALUES(?,?,'DEAD',now(),now(),?) ON CONFLICT DO NOTHING").use { s ->s.setLong(1,claim.id);s.setInt(2,claim.attempts);s.setString(3,reason.name);s.executeUpdate() }
   writer.insertAudit(c,claim.actorId,"RECONCILIATION_REQUIRED","sale",stored.projectionId,reason.name)
   AttemptOutcome.RECONCILIATION_REQUIRED
  }
 }
 override fun deferClaim(claim: ClaimedSaleCommand,reason: RecoveryReason,delay: Duration,reconciliation: Boolean): Boolean = transaction { c ->
  require(delay.seconds in 0..3600)
  val stored=lock(c,claim.command.quadruple.operationId)
  val updated=c.prepareStatement("UPDATE storecore_command_delivery SET state=?,last_error_code=?,next_attempt_at=now()+(? * interval '1 second'),lease_until=NULL,updated_at=now() WHERE command_id=? AND claim_token=? AND claim_epoch=? AND state='IN_FLIGHT' AND lease_until>now()").use { s ->
   s.setString(1,if(reconciliation) DeliveryState.RECONCILIATION_REQUIRED.name else DeliveryState.UNCERTAIN.name);s.setString(2,reason.name);s.setLong(3,delay.seconds);s.setLong(4,claim.id);s.setObject(5,claim.claimToken);s.setLong(6,claim.claimEpoch);s.executeUpdate()==1
  }
  if(updated && reconciliation) {
   c.prepareStatement("UPDATE sale_state_projection SET status='RECONCILIATION_REQUIRED',reconciliation_reason=?,version=version+1,updated_at=now() WHERE id=? AND version=? AND status IN ('PENDING_RESERVATION','RESERVED','PAYMENT_CAPTURED','COMMIT_PENDING','RELEASE_PENDING')").use { s ->s.setString(1,reason.name);s.setLong(2,stored.projectionId);s.setLong(3,stored.version);check(s.executeUpdate()==1) }
   writer.insertAudit(c,claim.actorId,"RECONCILIATION_REQUIRED","sale",stored.projectionId,reason.name)
  }
  updated
 }
 override fun applyClaimEvidence(claim: ClaimedSaleCommand,saga: SaleSaga,responseHash: String,remoteState: String): AttemptOutcome = transaction { c ->
  require(saga.quadruple==claim.command.quadruple)
  val stored=lock(c,saga.quadruple.operationId)
  val current=c.prepareStatement("SELECT state,claim_token,claim_epoch,lease_until>now() AS active FROM storecore_command_delivery WHERE command_id=? FOR UPDATE").use { s -> s.setLong(1,claim.id);s.executeQuery().use { r -> check(r.next());r.getString(1)==DeliveryState.IN_FLIGHT.name && r.getObject(2)==claim.claimToken && r.getLong(3)==claim.claimEpoch && r.getBoolean(4) } }
  val evidence=mapper.evidence(saga,remoteState);val evidenceHash=mapper.hash(evidence);val e=saga.evidence
  val inbox=c.prepareStatement("INSERT INTO storecore_inbox_events(client_instance_id,device_id,sale_id,operation_id,operation_kind,state,receipt,reservation_ref,response_hash,contract_version,openapi_digest,payload_redacted,evidence_json,evidence_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?,'{}',?::jsonb,?) ON CONFLICT(client_instance_id,device_id,sale_id,operation_id,operation_kind,response_hash) DO NOTHING RETURNING id").use { s ->
   s.setObject(1,UUID.fromString(saga.quadruple.clientInstanceId));s.setString(2,saga.quadruple.deviceId);s.setString(3,saga.quadruple.saleId);s.setObject(4,UUID.fromString(saga.quadruple.operationId));s.setString(5,claim.command.kind.name);s.setString(6,remoteState)
   s.setString(7,if(remoteState=="PENDING") null else e?.receipt);s.setObject(8,if(remoteState=="PENDING") null else e?.reservationRef?.let { ref -> runCatching {UUID.fromString(ref)}.getOrElse { UUID.nameUUIDFromBytes(ref.toByteArray()) } });s.setString(9,responseHash);s.setString(10,claim.command.contractVersion);s.setString(11,claim.command.openapiDigest);s.setString(12,evidence);s.setString(13,evidenceHash)
   s.executeQuery().use { r -> if(r.next()) r.getLong(1) else c.prepareStatement("SELECT id FROM storecore_inbox_events WHERE client_instance_id=? AND device_id=? AND sale_id=? AND operation_id=? AND operation_kind=? AND response_hash=? AND evidence_hash=?").use { q -> q.setObject(1,UUID.fromString(saga.quadruple.clientInstanceId));q.setString(2,saga.quadruple.deviceId);q.setString(3,saga.quadruple.saleId);q.setObject(4,UUID.fromString(saga.quadruple.operationId));q.setString(5,claim.command.kind.name);q.setString(6,responseHash);q.setString(7,evidenceHash);q.executeQuery().use { rows -> check(rows.next());rows.getLong(1) } } }
  }
  c.prepareStatement("INSERT INTO storecore_inbox_applications(inbox_id,command_id,claim_token,state,late_reason) VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING").use { s ->s.setLong(1,inbox);s.setLong(2,claim.id);s.setObject(3,claim.claimToken);s.setString(4,if(current) "APPLIED" else "LATE_IGNORED");s.setString(5,if(current) null else RecoveryReason.LEASE_EXPIRED.name);s.executeUpdate() }
  if(!current) AttemptOutcome.LATE_IGNORED else {
   val projection=findId(c,saga.quadruple.operationId)
   if(stored.saga.status!=saga.status) when(saga.status) {
    SaleStatus.RESERVED -> { require(e!=null && e.expiresAt!=null); writer.markReserved(c,projection,e.reservationRef,e.receipt,e.contractVersion,e.openapiDigest,e.expiresAt,json.writeValueAsString(e.acceptedPriceVersions)) }
    SaleStatus.COMMITTED,SaleStatus.RELEASED -> check(writer.advanceSaleStatus(c,projection,stored.saga.status.name,saga.status.name)==1)
    SaleStatus.RECONCILIATION_REQUIRED -> { require(e!=null); writer.markReconciliationRequired(c,projection,requireNotNull(saga.reconciliationReason),e.reservationRef,e.receipt,e.contractVersion,e.openapiDigest,json.writeValueAsString(e.acceptedPriceVersions)) }
    else -> require(saga.status==SaleStatus.PENDING_RESERVATION)
   }
   c.prepareStatement("UPDATE sale_state_projection SET version=version+1,updated_at=now() WHERE id=? AND version=?").use { s ->s.setLong(1,projection);s.setLong(2,stored.version);check(s.executeUpdate()==1) }
   val delivery=if(remoteState=="PENDING") DeliveryState.UNCERTAIN else DeliveryState.APPLIED
   c.prepareStatement("UPDATE storecore_command_delivery SET state=?,lease_until=NULL,next_attempt_at=now()+interval '1 second',updated_at=now() WHERE command_id=? AND claim_token=? AND claim_epoch=? AND state='IN_FLIGHT'").use { s ->s.setString(1,delivery.name);s.setLong(2,claim.id);s.setObject(3,claim.claimToken);s.setLong(4,claim.claimEpoch);check(s.executeUpdate()==1) }
   c.prepareStatement("INSERT INTO outbox_delivery_attempts(command_id,attempt_no,state,started_at,completed_at) VALUES(?,?,'SENT',now(),now()) ON CONFLICT DO NOTHING").use { s ->s.setLong(1,claim.id);s.setInt(2,claim.attempts);s.executeUpdate() }
   writer.insertAudit(c,claim.actorId,"REMOTE_EVIDENCE_APPLIED","sale",projection)
   if(delivery==DeliveryState.APPLIED) AttemptOutcome.APPLIED else AttemptOutcome.WAIT_AND_GET
  }
 }
 internal fun findId(c: Connection,operationId: String): Long=c.prepareStatement("SELECT id FROM sale_state_projection WHERE operation_id=?").use { s ->s.setObject(1,UUID.fromString(operationId));s.executeQuery().use { r ->check(r.next());val id=r.getLong(1);check(!r.next());id } }
}
