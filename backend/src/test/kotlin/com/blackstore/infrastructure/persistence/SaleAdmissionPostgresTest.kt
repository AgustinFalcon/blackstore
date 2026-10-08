package com.blackstore.infrastructure.persistence

import com.blackstore.domain.accounting.AccountingRuntimeState
import com.blackstore.domain.cash.StaffRole
import com.blackstore.domain.identity.*
import com.blackstore.domain.model.*
import com.blackstore.domain.port.out.accounting.AccountingLifecycleResult
import com.blackstore.domain.port.out.storecore.*
import com.blackstore.domain.sales.*
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.math.BigDecimal
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/** Isolated PG16 database. A direct URL must explicitly identify a disposable T08-B database. */
class SaleAdmissionPostgresTest {
    private val cashier=AuthenticatedStaff(StaffUserId(1),"cashier",StaffRole.CASHIER)
    private val other=AuthenticatedStaff(StaffUserId(3),"other",StaffRole.CASHIER)
    private val owner=AuthenticatedStaff(StaffUserId(2),"owner",StaffRole.OWNER)
    private val auditor=AuthenticatedStaff(StaffUserId(4),"auditor",StaffRole.AUDITOR)
    private val snapshot=CatalogSnapshot("catalog",Instant.EPOCH,null,StoreCoreContractRef(StoreCoreCanonicalContract.CANONICAL_PATH,StoreCoreCanonicalContract.VERSION,StoreCoreCanonicalContract.SHA256),false,
        listOf(CatalogItem("sku","Product","variant","price",BigDecimal("10.00"))))
    private val catalog=object: StoreCoreCatalogPort { override fun currentSnapshot()=snapshot }
    private fun adapter(source: DataSource)=JdbcSaleMutationCommands(source,catalog,StoreCoreCanonicalContract.CANONICAL_PATH,StoreCoreCanonicalContract.VERSION,StoreCoreCanonicalContract.SHA256)
    private fun command()=SaleCommand.Reserve(UUID.randomUUID(),OperationQuadruple(UUID.randomUUID().toString(),"device","sale",UUID.randomUUID().toString()),1,"variant",1,"price",TicketLine("sku","Product",1,BigDecimal("10.00"),BigDecimal("0.00")),null)
    private fun accepted(result: SaleCommandResult)=(result as SaleCommandResult.Accepted).receipt
    private fun failure(result: SaleCommandResult)=(result as SaleCommandResult.Rejected).failure

    @Test fun cleanAdmissionIsAtomicPendingAndReplaySurvivesPauseWithoutDispatch()=database { source ->
        val port=adapter(source);val draft=command()
        assertEquals(SaleCommandFailure.NotActivated,failure(port.execute(cashier,draft)))
        assertEquals("0",scalar(source,"SELECT count(*) FROM sale_intents"))
        sql(source,"UPDATE accounting_runtime SET state='ACTIVE',accounting_activation_at=clock_timestamp()")
        val receipt=accepted(port.execute(cashier,draft))
        assertEquals("PENDING_RESERVATION",scalar(source,"SELECT status FROM sale_state_projection"))
        assertEquals("PENDING",scalar(source,"SELECT state FROM storecore_command_delivery"))
        assertEquals("0",scalar(source,"SELECT count(*) FROM commercial_recognitions"))
        assertEquals("0",scalar(source,"SELECT count(*) FROM payments"))
        sql(source,"UPDATE accounting_runtime SET state='PAUSED'")
        assertEquals(receipt,accepted(port.execute(cashier,draft)))
        assertEquals(receipt,accepted(adapter(source).findReceipt(cashier,draft.commandId)))
        assertEquals(SaleCommandFailure.PayloadMismatch,failure(port.execute(cashier,draft.copy(reason="changed"))))
        assertEquals(SaleCommandFailure.Paused,failure(port.execute(cashier,command())))
        assertEquals("1",scalar(source,"SELECT count(*) FROM storecore_outbox_commands"))
        assertEquals("1",scalar(source,"SELECT count(*) FROM sale_command_admission_receipts"))
        assertEquals("1",scalar(source,"SELECT count(*) FROM audit_events WHERE event_type='RESERVE_REQUESTED'"))
        assertEquals(AccountingRuntimeState.Paused,(JdbcAccountingLifecycleQuery(source).observe(auditor) as AccountingLifecycleResult.Observed).observation.state)
    }
    @Test fun permissionOwnershipAndPayloadConflictsAreOpaqueAndCurrent()=database { source ->
        sql(source,"UPDATE accounting_runtime SET state='ACTIVE',accounting_activation_at=clock_timestamp()")
        val port=adapter(source);val draft=command();val receipt=accepted(port.execute(cashier,draft))
        assertEquals(SaleCommandResult.NotFound,port.findReceipt(other,receipt.commandId))
        assertEquals(SaleCommandResult.NotFound,port.findReceipt(other,UUID.randomUUID()))
        assertEquals(SaleCommandFailure.NotVisible,failure(port.execute(other,draft.copy(reason="mismatch"))))
        assertEquals(SaleCommandFailure.ExistingOperationCommand,failure(port.execute(cashier,draft.copy(commandId=UUID.randomUUID()))))
        assertEquals(SaleCommandFailure.PayloadMismatch,failure(port.execute(cashier,SaleCommand.Commit(draft.commandId,draft.identity,null))))
        assertEquals(SaleCommandFailure.Forbidden,failure(port.findReceipt(auditor,draft.commandId)))
        assertEquals(receipt,accepted(port.findReceipt(owner,draft.commandId)))
        assertEquals(SaleCommandFailure.Validation,failure(port.execute(owner,draft)))
        sql(source,"UPDATE staff_users SET active=false WHERE id=1")
        assertEquals(SaleCommandFailure.Forbidden,failure(port.findReceipt(cashier,draft.commandId)))
        assertEquals("1",scalar(source,"SELECT count(*) FROM storecore_outbox_commands"))
    }
    @Test fun concurrentSameIdCreatesOneReceiptAndGrantsPreventHistoricalMutation()=database { source ->
        sql(source,"UPDATE accounting_runtime SET state='ACTIVE',accounting_activation_at=clock_timestamp()")
        val draft=command();val pool=Executors.newFixedThreadPool(2)
        try {
            val futures=(1..2).map { pool.submit<SaleCommandResult> { adapter(source).execute(cashier,draft) } }
            val receipts=futures.map { accepted(it.get(10,TimeUnit.SECONDS)) }
            assertEquals(receipts[0],receipts[1])
        } finally { pool.shutdownNow() }
        assertEquals("1",scalar(source,"SELECT count(*) FROM sale_command_admission_receipts"))
        assertEquals("1",scalar(source,"SELECT count(*) FROM storecore_command_delivery"))
        assertThrows(SQLException::class.java) { source.connection.use { c -> c.autoCommit=false;c.createStatement().use { it.execute("SET LOCAL ROLE blackstore_app");it.execute("UPDATE sale_command_admission_receipts SET kind='Commit'") } } }
        assertThrows(SQLException::class.java) { sql(source,"DELETE FROM sale_command_admission_receipts") }
        assertEquals("false",scalar(source,"SELECT has_table_privilege('blackstore_app','sale_command_admission_receipts','UPDATE')::text"))
        assertEquals("false",scalar(source,"SELECT has_table_privilege('blackstore_projection_worker','sale_command_admission_receipts','INSERT')::text"))
        assertFalse(scalar(source,"SELECT current_user").startsWith("blackstore_"))
    }
    @Test fun upgradeV9PreservesLegacyHistoryAndDoesNotBackfillReceipts()=database(9) { source ->
        val q=command().identity
        source.connection.use { c -> c.autoCommit=false;JdbcBlackStoreWriter().insertPendingSale(c,UUID.fromString(q.clientInstanceId),q.deviceId,q.saleId,UUID.fromString(q.operationId),1,1,StoreCoreCanonicalContract.VERSION,StoreCoreCanonicalContract.SHA256);c.commit() }
        val before=scalar(source,"SELECT row_to_json(i)::text FROM sale_intents i")
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate()
        assertEquals(before,scalar(source,"SELECT row_to_json(i)::text FROM sale_intents i"))
        assertEquals("0",scalar(source,"SELECT count(*) FROM sale_command_admission_receipts"))
        assertEquals("10",scalar(source,"SELECT max(version::integer) FROM flyway_schema_history WHERE success AND version IS NOT NULL"))
    }
    private fun database(target: Int=10,block: (DataSource)->Unit) {
        val url=System.getenv("BLACKSTORE_T08B_TEST_JDBC_URL")
        if(url!=null) {
            require(url.endsWith("/blackstore_t08b_test")) { "Only an explicitly disposable T08-B database may be cleaned" }
            val source=PGSimpleDataSource().also { it.setURL(url);it.user=System.getenv("BLACKSTORE_T08B_TEST_JDBC_USER") ?: "postgres";it.password=System.getenv("BLACKSTORE_T08B_TEST_JDBC_PASSWORD") ?: "" }
            migrate(source,target,true);block(source);return
        }
        PostgreSQLContainer<Nothing>("postgres:16-alpine").use { db -> db.start();val source=PGSimpleDataSource().also { it.setURL(db.jdbcUrl);it.user=db.username;it.password=db.password };migrate(source,target,false);block(source) }
    }
    private fun migrate(source: DataSource,target: Int,clean: Boolean) {
        val flyway=Flyway.configure().cleanDisabled(false).target(target.toString()).dataSource(source).locations("classpath:db/migration").load()
        if(clean) flyway.clean()
        flyway.migrate()
        sql(source,"INSERT INTO roles(code) VALUES('CASHIER'),('OWNER'),('AUDITOR')")
        sql(source,"INSERT INTO staff_users(login,password_hash,role_code,display_name) VALUES('cashier','\$2test','CASHIER','Cashier'),('owner','\$2test','OWNER','Owner'),('other','\$2test','CASHIER','Other'),('auditor','\$2test','AUDITOR','Auditor')")
        sql(source,"INSERT INTO terminals(terminal_code) VALUES('POS')")
        sql(source,"INSERT INTO cash_session_projection(terminal_id,cashier_id,opened_at,opening_cash) VALUES(1,1,clock_timestamp(),0)")
    }
    private fun sql(source: DataSource,sql: String) { source.connection.use { c -> c.createStatement().use { it.execute(sql) } } }
    private fun scalar(source: DataSource,sql: String): String=source.connection.use { c -> c.createStatement().use { it.executeQuery(sql).use { r -> r.next();r.getString(1) } } }
}
