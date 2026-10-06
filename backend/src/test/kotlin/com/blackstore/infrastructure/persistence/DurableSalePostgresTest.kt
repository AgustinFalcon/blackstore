package com.blackstore.infrastructure.persistence

import com.blackstore.domain.model.*
import com.blackstore.domain.sales.*
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.sql.SQLException
import com.blackstore.application.sales.ReserveRequestFingerprint
import com.blackstore.domain.port.out.storecore.ReserveLineCommand
import com.blackstore.domain.identity.StaffUserId

@Testcontainers
class DurableSalePostgresTest {
 private fun source()=PGSimpleDataSource().apply { setURL(postgres.jdbcUrl);user=postgres.username;password=postgres.password }
 private fun sale(): SaleSaga {
  Flyway.configure().dataSource(postgres.jdbcUrl,postgres.username,postgres.password).locations("classpath:db/migration").load().migrate()
  val ids=source().connection.use { c ->
   c.createStatement().execute("INSERT INTO roles(code) VALUES('CASHIER') ON CONFLICT DO NOTHING")
   val user=c.createStatement().executeQuery("INSERT INTO staff_users(login,password_hash,role_code) VALUES('${UUID.randomUUID()}','\$2test','CASHIER') RETURNING id").use {r ->r.next();r.getLong(1)}
   val terminal=c.createStatement().executeQuery("INSERT INTO terminals(terminal_code) VALUES('${UUID.randomUUID()}') RETURNING id").use {r ->r.next();r.getLong(1)}
   val cash=c.createStatement().executeQuery("INSERT INTO cash_session_projection(terminal_id,cashier_id,opened_at,status) VALUES($terminal,$user,now(),'OPEN') RETURNING id").use {r ->r.next();r.getLong(1)}
   user to cash
  }
  val identity=OperationQuadruple(UUID.randomUUID().toString(),"device","sale",UUID.randomUUID().toString())
  val command=OutboxCommand(identity,StoreCoreOperationKind.RESERVE,"/blackstore-integration/v1","v1","a".repeat(64),"b".repeat(64),payload=CanonicalCommandPayload.Reserve("catalog",listOf(CanonicalReserveLine("variant",1,"price"))))
  return SaleSaga(identity,ids.second,lines=listOf(TicketLine("sku","name",1,BigDecimal("10.00"),BigDecimal("0.00"))),createdBy=ids.first,outbox=listOf(command))
 }
 @Test fun admissionReplayRollbackAndRestart() {
  val saga=sale();val store=JdbcSaleRecordStore(source());store.recordIntentAndOutbox(saga)
  store.recordIntentAndOutbox(saga)
  assertThrows<IllegalArgumentException> {store.recordIntentAndOutbox(saga.copy(lines=listOf(saga.lines.single().copy(quantity=2))))}
  val restarted=JdbcSaleRecordStore(source());val stored=restarted.findDurable(saga.quadruple.operationId)!!
  assertEquals(saga.lines,stored.saga.lines);assertEquals(saga.outbox,stored.saga.outbox);assertEquals(DurableSaleState.PENDING_RESERVATION,stored.state)
  val fresh=sale();val incomplete=fresh.copy(outbox=listOf(fresh.outbox.single().copy(payload=null)))
  assertThrows<IllegalArgumentException> {store.recordIntentAndOutbox(incomplete)}
  assertNull(store.findDurable(incomplete.quadruple.operationId))
 }
 @Test fun reserveReplayPreservesWireFingerprintAcrossNumericNormalization() {
  val original=sale();val wire=listOf(ReserveLineCommand("submitted-variant",1,"submitted-price"))
  val line=original.lines.single().copy(originalUnitPrice=BigDecimal("10"),discountAmount=BigDecimal.ZERO)
  val requestHash=ReserveRequestFingerprint.hash(original.quadruple,original.cashSessionId,wire,listOf(line),null)
  val saga=original.copy(lines=listOf(line),outbox=listOf(original.outbox.single().copy(requestHash=requestHash)))
  val store=JdbcSaleRecordStore(source());store.recordIntentAndOutbox(saga)
  val reloaded=JdbcSaleRecordStore(source()).findDurable(saga.quadruple.operationId)!!.saga
  assertEquals(requestHash,reloaded.outbox.single().requestHash)
  assertEquals(requestHash,ReserveRequestFingerprint.hash(saga.quadruple,saga.cashSessionId,wire,reloaded.lines,null))
  assertNotEquals(wire.single().variantId,(reloaded.outbox.single().payload as CanonicalCommandPayload.Reserve).lines.single().variantId)
  store.recordIntentAndOutbox(saga)
  assertThrows<IllegalArgumentException> { store.recordIntentAndOutbox(saga.copy(outbox=listOf(saga.outbox.single().copy(requestHash="d".repeat(64))))) }
 }
 @Test fun supervisorReserveRequiresAndPersistsOverrideReason() {
  val original=sale()
  val supervisor=source().connection.use { c ->
   c.createStatement().execute("INSERT INTO roles(code) VALUES('SUPERVISOR') ON CONFLICT DO NOTHING")
   c.createStatement().executeQuery("INSERT INTO staff_users(login,password_hash,role_code) VALUES('${UUID.randomUUID()}','\$2test','SUPERVISOR') RETURNING id").use { r ->r.next();r.getLong(1) }
  }
  val denied=original.copy(createdBy=supervisor,staffCommandAudit=SaleStaffCommandAudit(SaleStaffCommandEvent.RESERVE_REQUESTED,StaffUserId(supervisor),null))
  val store=JdbcSaleRecordStore(source())
  assertThrows<IllegalArgumentException> { store.recordIntentAndOutbox(denied) }
  assertNull(store.findDurable(original.quadruple.operationId))
  val admitted=denied.copy(staffCommandAudit=denied.staffCommandAudit!!.copy(reason="supervisor override"))
  store.recordIntentAndOutbox(admitted)
  source().connection.use { c ->c.prepareStatement("SELECT actor_id,business_reason FROM storecore_outbox_commands WHERE operation_id=?").use { s ->
   s.setObject(1,UUID.fromString(admitted.quadruple.operationId));s.executeQuery().use { r ->assertTrue(r.next());assertEquals(supervisor,r.getLong(1));assertEquals("supervisor override",r.getString(2)) }
  } }
 }
 @Test fun applicationIsAtomicAndLateClaimCannotOverwrite() {
  val saga=sale();val store=JdbcSaleRecordStore(source());store.recordIntentAndOutbox(saga)
  val first=store.claimCommand(saga.quadruple.operationId,CommandKind.RESERVE,Duration.ofSeconds(30))!!
  assertNull(store.claimCommand(saga.quadruple.operationId,CommandKind.RESERVE,Duration.ofSeconds(30)))
  source().connection.use { c ->c.prepareStatement("UPDATE storecore_command_delivery SET lease_until=now()-interval '1 second' WHERE command_id=?").use {s ->s.setLong(1,first.id);s.executeUpdate()} }
  val second=store.claimCommand(saga.quadruple.operationId,CommandKind.RESERVE,Duration.ofSeconds(30))!!
  assertTrue(second.uncertain);assertTrue(second.claimEpoch>first.claimEpoch)
  val evidence=RemoteEvidence(UUID.randomUUID().toString(),"receipt","v1","a".repeat(64),listOf("price"),Instant.now().plusSeconds(600))
  val reserved=saga.markReserved(evidence)
  assertEquals(AttemptOutcome.APPLIED,store.applyClaimEvidence(second,reserved,"c".repeat(64),"RESERVED"))
  assertEquals(AttemptOutcome.LATE_IGNORED,store.applyClaimEvidence(first,reserved,"c".repeat(64),"RESERVED"))
  assertEquals(SaleStatus.RESERVED,JdbcSaleRecordStore(source()).findDurable(saga.quadruple.operationId)!!.saga.status)
  source().connection.use { c ->
   c.createStatement().execute("SET ROLE blackstore_app")
   assertThrows<SQLException> {c.createStatement().execute("UPDATE storecore_outbox_commands SET payload_hash='${"d".repeat(64)}' WHERE id=${first.id}")}
   assertThrows<SQLException> {c.createStatement().execute("UPDATE sale_state_projection SET reservation_receipt='changed' WHERE operation_id='${saga.quadruple.operationId}'")}
   c.createStatement().execute("UPDATE sale_state_projection SET version=version+1,updated_at=now() WHERE operation_id='${saga.quadruple.operationId}'")
   c.createStatement().execute("RESET ROLE");c.createStatement().execute("SET ROLE blackstore_outbox_worker")
   assertThrows<SQLException> {c.createStatement().execute("UPDATE sale_state_projection SET version=version+1")}
  }
 }
 @Test fun reconciliationRollsBackInboxDeliveryAndProjectionWhenAuditFails() {
  val saga=sale();val store=JdbcSaleRecordStore(source());store.recordIntentAndOutbox(saga)
  val claim=store.claimCommand(saga.quadruple.operationId,CommandKind.RESERVE,Duration.ofSeconds(30))!!
  val remote=StoreCoreOperationReceipt(saga.quadruple,StoreCoreOperationKind.RESERVE,StoreCoreOperationState.EXPIRED,
   UUID.randomUUID().toString(),"expired",StoreCoreContractRef("/contract","v1","a".repeat(64)),listOf("price"),Instant.now())
  assertThrows<SQLException> { store.reconcileClaimEvidence(claim.copy(actorId=-1),remote,RecoveryReason.EXPIRED) }
  assertEquals(SaleStatus.PENDING_RESERVATION,store.findDurable(saga.quadruple.operationId)!!.saga.status)
  source().connection.use { c ->
   c.prepareStatement("SELECT state,(SELECT count(*) FROM storecore_inbox_events WHERE operation_id=?) FROM storecore_command_delivery WHERE command_id=?").use { s ->
    s.setObject(1,UUID.fromString(saga.quadruple.operationId));s.setLong(2,claim.id);s.executeQuery().use { r ->assertTrue(r.next());assertEquals("IN_FLIGHT",r.getString(1));assertEquals(0,r.getLong(2)) }
   }
  }
  assertEquals(AttemptOutcome.RECONCILIATION_REQUIRED,store.reconcileClaimEvidence(claim,remote,RecoveryReason.EXPIRED))
 }
 @Test fun invalidPayloadIsQuarantinedAndDoesNotStarveNextSale() {
  val poison=sale();val store=JdbcSaleRecordStore(source())
  source().connection.use { c ->c.createStatement().execute("UPDATE storecore_command_delivery SET state='LEGACY_INCOMPLETE'") }
  store.recordIntentAndOutbox(poison)
  val healthy=sale();store.recordIntentAndOutbox(healthy)
  source().connection.use { c ->
   c.autoCommit=false
   try {
    c.createStatement().execute("ALTER TABLE storecore_outbox_commands DISABLE TRIGGER USER")
    c.prepareStatement("UPDATE storecore_outbox_commands SET payload_hash=? WHERE operation_id=?").use { s ->s.setString(1,"0".repeat(64));s.setObject(2,UUID.fromString(poison.quadruple.operationId));s.executeUpdate() }
    c.createStatement().execute("ALTER TABLE storecore_outbox_commands ENABLE TRIGGER USER");c.commit()
   } catch(e: Exception) {c.rollback();throw e}
  }
  val claimed=store.claimNext(Duration.ofSeconds(30))!!
  assertEquals(healthy.quadruple,claimed.command.quadruple)
  assertEquals(DurableSaleState.LEGACY_INCOMPLETE,store.findDurable(poison.quadruple.operationId)!!.state)
  assertNull(store.claimCommand(poison.quadruple.operationId,CommandKind.RESERVE,Duration.ofSeconds(30)))
 }
 @Test fun reconciliationPreservesRemoteEvidenceAndLateResponseCannotReplaceIt() {
  val saga=sale();val store=JdbcSaleRecordStore(source());store.recordIntentAndOutbox(saga)
  val first=store.claimCommand(saga.quadruple.operationId,CommandKind.RESERVE,Duration.ofSeconds(30))!!
  source().connection.use { c ->c.prepareStatement("UPDATE storecore_command_delivery SET lease_until=now()-interval '1 second' WHERE command_id=?").use {s ->s.setLong(1,first.id);s.executeUpdate()} }
  val second=store.claimCommand(saga.quadruple.operationId,CommandKind.RESERVE,Duration.ofSeconds(30))!!
  val remoteIdentity=saga.quadruple.copy(operationId=UUID.randomUUID().toString())
  val remote=StoreCoreOperationReceipt(remoteIdentity,StoreCoreOperationKind.RESERVE,StoreCoreOperationState.EXPIRED,
   UUID.randomUUID().toString(),"expired-receipt",StoreCoreContractRef("/foreign-contract","remote-v2","f".repeat(64)),listOf("remote-price"),Instant.now())
  assertEquals(AttemptOutcome.RECONCILIATION_REQUIRED,store.reconcileClaimEvidence(second,remote,RecoveryReason.CONTRACT_INCOMPATIBLE))
  assertEquals(AttemptOutcome.LATE_IGNORED,store.reconcileClaimEvidence(first,remote.copy(receipt="late-receipt"),RecoveryReason.EXPIRED))
  val stored=store.findDurable(saga.quadruple.operationId)!!
  assertEquals(SaleStatus.RECONCILIATION_REQUIRED,stored.saga.status)
  assertEquals(RecoveryReason.CONTRACT_INCOMPATIBLE.name,stored.saga.reconciliationReason)
  source().connection.use { c ->
   c.prepareStatement("SELECT i.evidence_json,i.evidence_hash,a.state FROM storecore_inbox_events i JOIN storecore_inbox_applications a ON a.inbox_id=i.id WHERE a.command_id=? ORDER BY i.id").use { s ->
    s.setLong(1,second.id);s.executeQuery().use { r ->
     assertTrue(r.next());val node=com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(r.getString(1))
     assertEquals(remoteIdentity.operationId,node.path("operationId").asText());assertEquals("remote-v2",node.path("contractVersion").asText())
     assertEquals(remote.reservationRef,node.path("reservationRef").asText());assertEquals("EXPIRED",node.path("state").asText())
     assertEquals("expired-receipt",node.path("receipt").asText());assertEquals("APPLIED",r.getString(3))
     assertEquals(DurableCommandMapper().hash(DurableCommandMapper().remoteEvidence(remote)),r.getString(2))
     assertTrue(r.next());assertEquals("LATE_IGNORED",r.getString(3))
    }
   }
  }
 }
 companion object { @Container @JvmStatic val postgres=PostgreSQLContainer("postgres:16-alpine") }
}
