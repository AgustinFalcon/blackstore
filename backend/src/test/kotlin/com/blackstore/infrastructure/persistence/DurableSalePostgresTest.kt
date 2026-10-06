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
 companion object { @Container @JvmStatic val postgres=PostgreSQLContainer("postgres:16-alpine") }
}
