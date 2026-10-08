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
import java.lang.reflect.Proxy
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import com.blackstore.domain.pos.PosContextResult

/** Isolated PG16 database. A direct URL must explicitly identify a disposable T08-B database. */
class SaleAdmissionPostgresTest {
    private val cashier=AuthenticatedStaff(StaffUserId(1),"cashier",StaffRole.CASHIER)
    private val other=AuthenticatedStaff(StaffUserId(3),"other",StaffRole.CASHIER)
    private val owner=AuthenticatedStaff(StaffUserId(2),"owner",StaffRole.OWNER)
    private val auditor=AuthenticatedStaff(StaffUserId(4),"auditor",StaffRole.AUDITOR)
    private val snapshot=CatalogSnapshot("catalog",Instant.EPOCH,null,StoreCoreContractRef(StoreCoreCanonicalContract.CANONICAL_PATH,StoreCoreCanonicalContract.VERSION,StoreCoreCanonicalContract.SHA256),false,
        listOf(CatalogItem("sku","Product","variant","price",BigDecimal("10.00"))))
    private val catalog=object: StoreCoreCatalogPort { override fun currentSnapshot()=snapshot }
    private val client="11111111-1111-4111-8111-111111111111"
    private fun adapter(source: DataSource, terminal: Long=1, connector: String=client)=JdbcSaleMutationCommands(source,catalog,StoreCoreCanonicalContract.CANONICAL_PATH,StoreCoreCanonicalContract.VERSION,StoreCoreCanonicalContract.SHA256,terminalId=terminal,connectorClientInstanceId=connector)
    private fun command()=SaleCommand.Reserve(UUID.randomUUID(),OperationQuadruple(client,"device","sale",UUID.randomUUID().toString()),1,"variant",1,"price",TicketLine("sku","Product",1,BigDecimal("10.00"),BigDecimal("0.00")),null)
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
        assertEquals(SaleCommandFailure.NotVisible,failure(port.execute(cashier,draft.copy(commandId=UUID.randomUUID(),identity=draft.identity.copy(deviceId="wrong")))))
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
        assertEquals("11",scalar(source,"SELECT max(version::integer) FROM flyway_schema_history WHERE success AND version IS NOT NULL"))
    }
    @Test fun unprovisionedCleanDatabaseAndUpgradeNeverInventContext()=database(10) { source ->
        val draft=command()
        source.connection.use { c -> c.autoCommit=false;JdbcBlackStoreWriter().insertPendingSale(c,UUID.fromString(client),"legacy-device",draft.identity.saleId,UUID.fromString(draft.identity.operationId),1,1,StoreCoreCanonicalContract.VERSION,StoreCoreCanonicalContract.SHA256);c.commit() }
        val before=scalar(source,"SELECT row_to_json(i)::text FROM sale_intents i")
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate()
        assertEquals(before,scalar(source,"SELECT row_to_json(i)::text FROM sale_intents i"))
        assertEquals("0",scalar(source,"SELECT count(*) FROM pos_terminal_context"))
        assertEquals(PosContextResult.Unavailable,JdbcPosContextQuery(source,1,client).observe(cashier))
        assertEquals("0",scalar(source,"SELECT count(*) FROM sale_command_admission_receipts"))
    }
    @Test fun contextMismatchesRejectWithoutIntentOutboxOrReceiptAfterVisibility()=database { source ->
        sql(source,"UPDATE accounting_runtime SET state='ACTIVE',accounting_activation_at=clock_timestamp()")
        val draft=command()
        for(bad in listOf(draft.copy(identity=draft.identity.copy(deviceId="wrong")),draft.copy(identity=draft.identity.copy(clientInstanceId="33333333-3333-4333-8333-333333333333"))))
            assertEquals(SaleCommandFailure.Validation,failure(adapter(source).execute(cashier,bad)))
        assertEquals(SaleCommandFailure.Unavailable,failure(adapter(source,connector="33333333-3333-4333-8333-333333333333").execute(cashier,draft)))
        assertEquals(SaleCommandFailure.Unavailable,failure(adapter(source,terminal=999).execute(cashier,draft)))
        sql(source,"INSERT INTO terminals(terminal_code) VALUES('OTHER'); INSERT INTO cash_session_projection(terminal_id,cashier_id,opened_at,opening_cash) VALUES(2,3,clock_timestamp(),0)")
        assertEquals(SaleCommandFailure.Validation,failure(adapter(source).execute(other,draft.copy(cashSessionId=2))))
        sql(source,"UPDATE terminals SET active=false WHERE id=1")
        assertEquals(SaleCommandFailure.NotVisible,failure(adapter(source).execute(other,draft)))
        assertEquals(SaleCommandFailure.Unavailable,failure(adapter(source).execute(cashier,draft)))
        for(table in listOf("sale_intents","storecore_outbox_commands","sale_command_admission_receipts")) assertEquals("0",scalar(source,"SELECT count(*) FROM $table"))
    }
    @Test fun missingBindingAndInactiveTerminalAreUnavailableWithoutWrites()=database(provision=false) { source ->
        sql(source,"UPDATE accounting_runtime SET state='ACTIVE',accounting_activation_at=clock_timestamp()")
        assertEquals(SaleCommandFailure.Unavailable,failure(adapter(source).execute(cashier,command())))
        assertEquals(PosContextResult.Unavailable,JdbcPosContextQuery(source,1,client).observe(cashier))
        assertEquals("0",scalar(source,"SELECT count(*) FROM sale_intents"))
        assertEquals("0",scalar(source,"SELECT count(*) FROM storecore_outbox_commands"))
        assertEquals("0",scalar(source,"SELECT count(*) FROM sale_command_admission_receipts"))
    }
    @Test fun authorizedReplaySurvivesUnavailableCurrentContextClosedCashAndPausedRuntime()=database { source ->
        sql(source,"UPDATE accounting_runtime SET state='ACTIVE',accounting_activation_at=clock_timestamp()")
        val draft=command();val saved=accepted(adapter(source).execute(cashier,draft))
        sql(source,"UPDATE accounting_runtime SET state='PAUSED'; UPDATE terminals SET active=false; UPDATE cash_session_projection SET status='CLOSED',closed_at=clock_timestamp(),closing_cash_declared=0")
        val unavailable=adapter(source,terminal=999,connector="")
        assertEquals(saved,accepted(unavailable.execute(cashier,draft)))
        assertEquals(SaleCommandFailure.PayloadMismatch,failure(unavailable.execute(cashier,draft.copy(reason="different"))))
        assertEquals(SaleCommandFailure.NotVisible,failure(unavailable.execute(other,draft.copy(reason="different"))))
        assertEquals(saved,accepted(unavailable.findReceipt(cashier,draft.commandId)))
        assertEquals("1",scalar(source,"SELECT count(*) FROM sale_intents"))
        assertEquals("1",scalar(source,"SELECT count(*) FROM storecore_outbox_commands"))
    }
    @Test fun contextQueryIsReadOnlyRevalidatesRoleAndBindingsAreImmutableWithMinimalGrants()=database { source ->
        val before=scalar(source,"SELECT count(*) FROM audit_events")
        assertTrue(JdbcPosContextQuery(source,1,client).observe(cashier) is PosContextResult.Available)
        assertEquals(before,scalar(source,"SELECT count(*) FROM audit_events"))
        assertEquals("0",scalar(source,"SELECT count(*) FROM storecore_outbox_commands"))
        assertThrows(StaffSecurityException::class.java) { JdbcPosContextQuery(source,1,client).observe(auditor) }
        sql(source,"UPDATE staff_users SET active=false WHERE id=1")
        assertThrows(StaffSecurityException::class.java) { JdbcPosContextQuery(source,1,client).observe(cashier) }
        for(action in listOf("UPDATE","DELETE","INSERT","TRUNCATE")) assertEquals("false",scalar(source,"SELECT has_table_privilege('blackstore_app','pos_terminal_context','$action')::text"))
        for(role in listOf("blackstore_auditor","blackstore_projection_worker","blackstore_outbox_worker")) assertEquals("false",scalar(source,"SELECT has_table_privilege('$role','pos_terminal_context','SELECT')::text"))
        assertEquals("false",scalar(source,"SELECT has_table_privilege('blackstore_app','terminals','UPDATE')::text"))
        for(change in listOf("UPDATE pos_terminal_context SET device_id='replacement'","DELETE FROM pos_terminal_context","TRUNCATE pos_terminal_context")) assertThrows(SQLException::class.java) { sql(source,change) }
        assertThrows(SQLException::class.java) { sql(source,"INSERT INTO pos_terminal_context(terminal_id,client_instance_id,device_id,provisioned_by_ref,installation_evidence_ref) VALUES(1,'$client','device','admin','fixture')") }
        sql(source,"INSERT INTO terminals(terminal_code) VALUES('SECOND')")
        assertThrows(SQLException::class.java) { sql(source,"INSERT INTO pos_terminal_context(terminal_id,client_instance_id,device_id,provisioned_by_ref,installation_evidence_ref) VALUES(2,'$client','device','admin','fixture')") }
        assertThrows(SQLException::class.java) { sql(source,"INSERT INTO pos_terminal_context(terminal_id,client_instance_id,device_id,provisioned_by_ref,installation_evidence_ref) VALUES(2,'$client',' ','admin','fixture')") }
    }
    @Test fun staleGetThenDeactivationWinningLockRejectsAdmission()=database { source ->
        sql(source,"UPDATE accounting_runtime SET state='ACTIVE',accounting_activation_at=clock_timestamp()")
        assertTrue(JdbcPosContextQuery(source,1,client).observe(cashier) is PosContextResult.Available)
        val pool=Executors.newSingleThreadExecutor()
        try { source.connection.use { admin ->
            admin.autoCommit=false
            admin.createStatement().execute("UPDATE terminals SET active=false WHERE id=1")
            val future=pool.submit<SaleCommandResult> { adapter(source).execute(cashier,command()) }
            awaitBlocked(source)
            assertFalse(future.isDone)
            admin.commit()
            assertEquals(SaleCommandFailure.Unavailable,failure(future.get(10,TimeUnit.SECONDS)))
            assertEquals("0",scalar(source,"SELECT count(*) FROM sale_intents"))
        } } finally { pool.shutdownNow() }
    }
    @Test fun reserveWinningTerminalLockProtectsActiveBindingUntilPhysicalCommit()=database { source ->
        sql(source,"UPDATE accounting_runtime SET state='ACTIVE',accounting_activation_at=clock_timestamp()")
        val paused=CommitBarrierSource(source);val pool=Executors.newFixedThreadPool(2)
        try {
            val reserve=pool.submit<SaleCommandResult> { adapter(paused).execute(cashier,command()) }
            assertTrue(paused.reached.await(10,TimeUnit.SECONDS))
            val deactivate=pool.submit { sql(source,"UPDATE terminals SET active=false WHERE id=1") }
            awaitBlocked(source);assertFalse(deactivate.isDone)
            paused.proceed.countDown()
            accepted(reserve.get(10,TimeUnit.SECONDS));deactivate.get(10,TimeUnit.SECONDS)
            assertEquals("1",scalar(source,"SELECT count(*) FROM sale_intents"))
            assertEquals(SaleCommandFailure.Unavailable,failure(adapter(source).execute(cashier,command())))
        } finally { paused.proceed.countDown();pool.shutdownNow() }
    }
    private fun awaitBlocked(source: DataSource) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8)
        while(System.nanoTime()<deadline) {
            if(scalar(source,"SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'").toInt()>0) return
            Thread.sleep(20)
        }
        fail<Unit>("Expected database lock wait")
    }
    private class CommitBarrierSource(private val delegate: DataSource): DataSource by delegate {
        val reached=CountDownLatch(1);val proceed=CountDownLatch(1)
        override fun getConnection(): java.sql.Connection {
            val physical=delegate.connection
            return Proxy.newProxyInstance(java.sql.Connection::class.java.classLoader,arrayOf(java.sql.Connection::class.java)) { _,method,args ->
                if(method.name=="commit") { reached.countDown();check(proceed.await(10,TimeUnit.SECONDS)) }
                try { method.invoke(physical,*(args ?: emptyArray())) } catch(e: InvocationTargetException) { throw e.targetException }
            } as java.sql.Connection
        }
    }
    private fun database(target: Int=11, provision: Boolean=true,block: (DataSource)->Unit) {
        val url=System.getenv("BLACKSTORE_T08B_TEST_JDBC_URL")
        if(url!=null) {
            require(url.endsWith("/blackstore_t08b_test")) { "Only an explicitly disposable T08-B database may be cleaned" }
            val source=PGSimpleDataSource().also { it.setURL(url);it.user=System.getenv("BLACKSTORE_T08B_TEST_JDBC_USER") ?: "postgres";it.password=System.getenv("BLACKSTORE_T08B_TEST_JDBC_PASSWORD") ?: "" }
            migrate(source,target,true,provision);block(source);return
        }
        PostgreSQLContainer<Nothing>("postgres:16-alpine").use { db -> db.start();val source=PGSimpleDataSource().also { it.setURL(db.jdbcUrl);it.user=db.username;it.password=db.password };migrate(source,target,false,provision);block(source) }
    }
    private fun migrate(source: DataSource,target: Int,clean: Boolean,provision: Boolean) {
        val flyway=Flyway.configure().cleanDisabled(false).target(target.toString()).dataSource(source).locations("classpath:db/migration").load()
        if(clean) flyway.clean()
        flyway.migrate()
        sql(source,"INSERT INTO roles(code) VALUES('CASHIER'),('OWNER'),('AUDITOR')")
        sql(source,"INSERT INTO staff_users(login,password_hash,role_code,display_name) VALUES('cashier','\$2test','CASHIER','Cashier'),('owner','\$2test','OWNER','Owner'),('other','\$2test','CASHIER','Other'),('auditor','\$2test','AUDITOR','Auditor')")
        sql(source,"INSERT INTO terminals(terminal_code) VALUES('POS')")
        sql(source,"INSERT INTO cash_session_projection(terminal_id,cashier_id,opened_at,opening_cash) VALUES(1,1,clock_timestamp(),0)")
        if(target>=11 && provision) sql(source,"INSERT INTO pos_terminal_context(terminal_id,client_instance_id,device_id,provisioned_by_ref,installation_evidence_ref) VALUES(1,'$client','device','test-admin','isolated-fixture')")
    }
    private fun sql(source: DataSource,sql: String) { source.connection.use { c -> c.createStatement().use { it.execute(sql) } } }
    private fun scalar(source: DataSource,sql: String): String=source.connection.use { c -> c.createStatement().use { it.executeQuery(sql).use { r -> r.next();r.getString(1) } } }
}
