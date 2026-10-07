package com.blackstore.infrastructure.persistence

import com.blackstore.domain.cash.*
import com.blackstore.domain.identity.*
import com.blackstore.domain.ledger.ExpenseRecord
import com.blackstore.domain.sales.PaymentMethod
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Native loopback DB opt-in locally; the same tests use a fresh PG16 container in CI. */
class DurableCashPostgresTest {
    private data class Fixture(val staff: AuthenticatedStaff, val terminal: Long)
    private val now=Instant.parse("2026-10-06T12:00:00Z")
    private fun fixture(role: StaffRole=StaffRole.CASHIER): Fixture {
        migrate()
        return admin().connection.use { c ->
            c.prepareStatement("INSERT INTO roles(code) VALUES(?) ON CONFLICT DO NOTHING").use { s ->s.setString(1,role.name);s.executeUpdate() }
            c.prepareStatement("INSERT INTO staff_users(login,password_hash,role_code) VALUES(?, ?, ?) RETURNING id").use { s ->
                s.setString(1,UUID.randomUUID().toString());s.setString(2,"\$2test");s.setString(3,role.name)
                val id=s.executeQuery().use { r ->r.next();r.getLong(1) }
                val terminal=c.createStatement().executeQuery("INSERT INTO terminals(terminal_code) VALUES('${UUID.randomUUID()}') RETURNING id").use { r ->r.next();r.getLong(1) }
                Fixture(AuthenticatedStaff(StaffUserId(id),"Test operator",role),terminal)
            }
        }
    }
    private fun commands()=JdbcCashMutationCommands(runtime())
    private fun open(f: Fixture)=commands().open(f.staff,f.terminal,f.staff.id.value,BigDecimal.ZERO,null,now).recordOrThrow()
    private fun expense(f: Fixture, cash: CashSession)=ExpenseRecord(987654,cash.id,"supplies",BigDecimal("1.23"),"supplies expense",PaymentMethod.CASH,f.staff.id.value,now)
    private fun count(sql: String): Long=admin().connection.use { c ->c.createStatement().executeQuery(sql).use { r ->r.next();r.getLong(1) } }
    private fun rejected(result: CashMutationResult<*>, failure: CashMutationFailure) = assertEquals(CashMutationResult.Rejected(failure),result)

    @Test fun lifecycleHasGeneratedIdentityAndCompleteAuditsAcrossNewAdapters() {
        val f=fixture();val cash=open(f)
        val stored=commands().expense(f.staff,expense(f,cash)).recordOrThrow()
        assertNotEquals(987654,stored.id)
        assertEquals(BigDecimal("1.23"),stored.amount)
        val closed=commands().close(f.staff,cash.id,BigDecimal("0"),null,now.plusSeconds(1)).recordOrThrow()
        assertEquals(CashSessionStatus.CLOSED,closed.status)
        assertEquals(BigDecimal("0.00"),JdbcCashSessionStore(runtime()).list().single { it.id==cash.id }.closingCashDeclared)
        assertEquals(2,count("SELECT count(*) FROM audit_events WHERE aggregate_type='cash_session' AND aggregate_id=${cash.id}"))
        assertEquals(1,count("SELECT count(*) FROM audit_events WHERE aggregate_type='expense' AND aggregate_id=${stored.id} AND actor_id=${f.staff.id.value}"))
        rejected(commands().close(f.staff,cash.id,BigDecimal.TEN,null,now.plusSeconds(2)),CashMutationFailure.Conflict)
        rejected(commands().expense(f.staff,expense(f,cash)),CashMutationFailure.Conflict)
        assertEquals(1,count("SELECT count(*) FROM expenses WHERE cash_session_id=${cash.id}"))
    }
    @Test fun samePhysicalConnectionCannotRetainRuntimeRoleAfterCommitOrRollback() {
        val f=fixture()
        runtime().connection.use { physical ->
            val pooled=object : javax.sql.DataSource by runtime() {
                override fun getConnection(): java.sql.Connection = java.lang.reflect.Proxy.newProxyInstance(java.sql.Connection::class.java.classLoader,arrayOf(java.sql.Connection::class.java)) { _,method,args ->
                    if(method.name=="close") null else try { method.invoke(physical,*(args ?: emptyArray())) } catch(error: java.lang.reflect.InvocationTargetException) { throw error.targetException }
                } as java.sql.Connection
            }
            val command=JdbcCashMutationCommands(pooled)
            val cash=command.open(f.staff,f.terminal,f.staff.id.value,BigDecimal.ZERO,null,now).recordOrThrow()
            fun assertRole() { physical.createStatement().executeQuery("SELECT current_user").use { r ->r.next();assertEquals("dct_runtime",r.getString(1)) };physical.rollback() }
            assertRole()
            rejected(command.close(f.staff,cash.id,BigDecimal("0.001"),null,now),CashMutationFailure.Validation)
            assertRole()
            command.close(f.staff,cash.id,BigDecimal.ZERO,null,now).recordOrThrow()
            assertRole()
        }
    }
    @Test fun expenseReasonControlCharactersAreSerializedAndBothRowsCommitExactlyOnce() {
        val f=fixture();val cash=open(f)
        val reason="invoice\n\tdetail\r\u0001\u0008\u001f quote \" and slash \\"
        val stored=commands().expense(f.staff,expense(f,cash).copy(reason=reason)).recordOrThrow()
        assertEquals(reason,stored.reason)
        admin().connection.use { c ->
            c.prepareStatement("SELECT id,reason,created_by FROM expenses WHERE cash_session_id=?").use { s ->
                s.setLong(1,cash.id)
                s.executeQuery().use { r ->
                    assertTrue(r.next());assertEquals(stored.id,r.getLong(1));assertEquals(reason,r.getString(2));assertEquals(f.staff.id.value,r.getLong(3));assertFalse(r.next())
                }
            }
            c.prepareStatement("SELECT event_type,actor_id,payload_redacted FROM audit_events WHERE aggregate_type='expense' AND aggregate_id=?").use { s ->
                s.setLong(1,stored.id)
                s.executeQuery().use { r ->
                    assertTrue(r.next());assertEquals(CashAuditEventType.EXPENSE_RECORDED.name,r.getString(1));assertEquals(f.staff.id.value,r.getLong(2))
                    val payload=com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(r.getString(3))
                    assertEquals(setOf("source","detail"),payload.fieldNames().asSequence().toSet())
                    assertEquals("blackstore",payload["source"].asText());assertEquals(reason,payload["detail"].asText());assertFalse(r.next())
                }
            }
        }
    }
    @Test fun visibilityAndExistingUniqueIndexesNeverDiscloseAnotherCashier() {
        val own=fixture();val other=fixture();val cash=open(own)
        rejected(commands().open(own.staff,other.terminal,own.staff.id.value,BigDecimal.ZERO,null,now),CashMutationFailure.Conflict)
        rejected(commands().open(other.staff,own.terminal,other.staff.id.value,BigDecimal.ZERO,null,now),CashMutationFailure.NotVisible)
        rejected(commands().close(other.staff,cash.id,BigDecimal.ZERO,null,now),CashMutationFailure.NotVisible)
        commands().close(own.staff,cash.id,BigDecimal.ZERO,null,now).recordOrThrow()
        rejected(commands().expense(other.staff,expense(other,cash)),CashMutationFailure.NotVisible)
        rejected(commands().close(other.staff,Long.MAX_VALUE,BigDecimal.ZERO,null,now),CashMutationFailure.NotVisible)
        val owner=fixture(StaffRole.OWNER)
        rejected(commands().close(owner.staff,cash.id,BigDecimal.ZERO,null,now),CashMutationFailure.Validation)
        rejected(commands().close(owner.staff,cash.id,BigDecimal.ZERO,"override",now),CashMutationFailure.Conflict)
        val auditor=fixture(StaffRole.AUDITOR)
        rejected(commands().close(auditor.staff,cash.id,BigDecimal.ZERO,"override",now),CashMutationFailure.Forbidden)
    }
    @Test fun dualBlockersMatchMemoryVisibilityInBothInsertionOrders() {
        for(visibleFirst in listOf(true,false)) {
            val own=fixture();val other=fixture()
            val ordered=if(visibleFirst) listOf(own,other) else listOf(other,own)
            val sessions=ordered.map { open(it) }
            rejected(commands().open(own.staff,other.terminal,own.staff.id.value,BigDecimal.ZERO,null,now),CashMutationFailure.NotVisible)
            val ids=sessions.joinToString(",") { it.id.toString() }
            assertEquals(2,count("SELECT count(*) FROM cash_session_projection WHERE id IN ($ids)"))
            assertEquals(2,count("SELECT count(*) FROM audit_events WHERE aggregate_type='cash_session' AND aggregate_id IN ($ids)"))
        }
    }
    @Test fun auditFailureRollsBackEachFirstWriteAndRoleAuthority() {
        val f=fixture();val cash=open(f)
        admin().connection.use { c ->c.createStatement().execute("""
            CREATE FUNCTION dct_fail_audit() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'} BEGIN RAISE EXCEPTION 'isolated audit failpoint'; END; ${'$'}${'$'};
            CREATE TRIGGER dct_fail_audit BEFORE INSERT ON audit_events FOR EACH ROW EXECUTE FUNCTION dct_fail_audit();
        """.trimIndent()) }
        try {
            val second=fixture()
            rejected(commands().open(second.staff,second.terminal,second.staff.id.value,BigDecimal.ZERO,null,now),CashMutationFailure.Unavailable)
            assertEquals(0,count("SELECT count(*) FROM cash_session_projection WHERE cashier_id=${second.staff.id.value}"))
            rejected(commands().expense(f.staff,expense(f,cash)),CashMutationFailure.Unavailable)
            assertEquals(0,count("SELECT count(*) FROM expenses WHERE cash_session_id=${cash.id}"))
            rejected(commands().close(f.staff,cash.id,BigDecimal.TEN,null,now),CashMutationFailure.Unavailable)
            assertEquals(CashSessionStatus.OPEN,JdbcCashSessionStore(runtime()).list().single { it.id==cash.id }.status)
        } finally { admin().connection.use { c ->c.createStatement().execute("DROP TRIGGER dct_fail_audit ON audit_events; DROP FUNCTION dct_fail_audit()") } }
        runtime().connection.use { c ->
            c.createStatement().executeQuery("SELECT current_user").use { r ->r.next();assertEquals("dct_runtime",r.getString(1)) }
            org.junit.jupiter.api.assertThrows<java.sql.SQLException> { c.createStatement().execute("UPDATE cash_session_projection SET opening_cash=1 WHERE id=${cash.id}") }
        }
        commands().close(f.staff,cash.id,BigDecimal.ZERO,null,now).recordOrThrow()
    }
    @Test fun concurrentOpensAndClosesHaveOneWinnerAndNoDuplicateAudit() {
        val f=fixture();val barrier=CyclicBarrier(2);val pool=Executors.newFixedThreadPool(2)
        try {
            val results=(1..2).map { pool.submit(Callable { barrier.await(); commands().open(f.staff,f.terminal,f.staff.id.value,BigDecimal.ZERO,null,now) }) }.map { it.get(15,TimeUnit.SECONDS) }
            assertEquals(1,results.count { it is CashMutationResult.Applied })
            assertEquals(1,results.count { it==CashMutationResult.Rejected(CashMutationFailure.Conflict) })
            val cash=(results.first { it is CashMutationResult.Applied } as CashMutationResult.Applied).record
            val closeBarrier=CyclicBarrier(2)
            val closes=(1..2).map { pool.submit(Callable { closeBarrier.await();commands().close(f.staff,cash.id,BigDecimal(it),null,now) }) }.map { it.get(15,TimeUnit.SECONDS) }
            assertEquals(1,closes.count { it is CashMutationResult.Applied })
            assertEquals(1,closes.count { it==CashMutationResult.Rejected(CashMutationFailure.Conflict) })
            assertEquals(2,count("SELECT count(*) FROM audit_events WHERE aggregate_type='cash_session' AND aggregate_id=${cash.id}"))
        } finally { pool.shutdownNow() }
    }
    @Test fun durableRoleAndExactMoneyAreRevalidatedRatherThanTrustingRequestSnapshot() {
        val f=fixture();val cash=open(f)
        admin().connection.use { c ->c.createStatement().execute("UPDATE staff_users SET role_code='AUDITOR' WHERE id=${f.staff.id.value}") }
        rejected(commands().close(f.staff,cash.id,BigDecimal.ZERO,null,now),CashMutationFailure.Forbidden)
        admin().connection.use { c ->c.createStatement().execute("UPDATE staff_users SET role_code='CASHIER' WHERE id=${f.staff.id.value}") }
        rejected(commands().close(f.staff,cash.id,BigDecimal("0.001"),null,now),CashMutationFailure.Validation)
        rejected(commands().expense(f.staff,expense(f,cash).copy(paymentMethod=PaymentMethod.UNKNOWN)),CashMutationFailure.Validation)
        assertEquals(0,count("SELECT count(*) FROM expenses WHERE cash_session_id=${cash.id}"))
        val fresh=fixture()
        rejected(commands().open(fresh.staff,Long.MAX_VALUE,fresh.staff.id.value,BigDecimal.ZERO,null,now),CashMutationFailure.Unavailable)
    }
    @Test fun rowLockOrdersExpenseBeforeCloseAndCloseBeforeExpenseAndRollbackReleasesIt() {
        for(firstCloses in listOf(false,true)) {
            val f=fixture();val cash=open(f);val pool=Executors.newSingleThreadExecutor()
            admin().connection.use { first ->
                first.autoCommit=false
                first.createStatement().executeQuery("SELECT id FROM cash_session_projection WHERE id=${cash.id} FOR UPDATE").close()
                try {
                    val follower=pool.submit(Callable { if(firstCloses) commands().expense(f.staff,expense(f,cash)) else commands().close(f.staff,cash.id,BigDecimal.ZERO,null,now) })
                    awaitRowLock()
                    val writer=JdbcBlackStoreWriter()
                    if(firstCloses) {
                        writer.closeCashSession(first,cash.id,BigDecimal.ZERO,now)
                        writer.insertAudit(first,f.staff.id.value,CashAuditEventType.CASH_SESSION_CLOSED.name,"cash_session",cash.id,detail="test first close")
                    } else {
                        val id=writer.insertExpense(first,cash.id,"supplies",BigDecimal.ONE,"expense",PaymentMethod.CASH.name,f.staff.id.value,now)
                        writer.insertAudit(first,f.staff.id.value,CashAuditEventType.EXPENSE_RECORDED.name,"expense",id,detail="test first expense")
                    }
                    first.commit()
                    val result=follower.get(10,TimeUnit.SECONDS)
                    if(firstCloses) rejected(result,CashMutationFailure.Conflict) else assertTrue(result is CashMutationResult.Applied)
                    assertEquals(if(firstCloses) 0 else 1,count("SELECT count(*) FROM expenses WHERE cash_session_id=${cash.id}"))
                } finally { first.rollback();pool.shutdownNow() }
            }
        }
        val f=fixture();val cash=open(f);val pool=Executors.newSingleThreadExecutor()
        admin().connection.use { first ->
            first.autoCommit=false
            first.createStatement().executeQuery("SELECT id FROM cash_session_projection WHERE id=${cash.id} FOR UPDATE").close()
            try {
                val follower=pool.submit(Callable { commands().expense(f.staff,expense(f,cash)) })
                awaitRowLock()
                // The request already waited for the cash lock; revalidation must see this role change.
                admin().connection.use { c ->c.createStatement().execute("UPDATE staff_users SET role_code='AUDITOR' WHERE id=${f.staff.id.value}") }
                first.rollback()
                rejected(follower.get(10,TimeUnit.SECONDS),CashMutationFailure.Forbidden)
                assertEquals(0,count("SELECT count(*) FROM expenses WHERE cash_session_id=${cash.id}"))
            } finally { first.rollback();pool.shutdownNow() }
        }
    }
    private fun awaitRowLock() {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
        while(System.nanoTime()<deadline) {
            if(count("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE 'SELECT * FROM cash_session_projection WHERE id=% FOR UPDATE'")>0) return
            Thread.onSpinWait()
        }
        fail<Unit>("competing physical connection never waited on the cash row lock")
    }
    @Test fun realChildProcessCrashesBeforeCommitAndAfterCommitPreserveWholeFacts() {
        val readerPool=Executors.newSingleThreadExecutor()
        try {
            for (operation in CashCrashOperation.entries.filter { it!=CashCrashOperation.UNKNOWN }) for(boundary in CashCrashBoundary.entries.filter { it!=CashCrashBoundary.UNKNOWN }) {
                val f=fixture();val cash=if(operation==CashCrashOperation.OPEN) null else open(f)
                val appName="dct-crash-${UUID.randomUUID()}"
                val url=native ?: container.jdbcUrl
                val child=ProcessBuilder(System.getProperty("java.home")+"/bin/java", "-cp",System.getProperty("blackstore.test.classpath"),DurableCashCrashChild::class.java.name,
                    url,boundary.name,operation.name,f.staff.id.value.toString(),f.terminal.toString(),(cash?.id ?: 0).toString(),appName).redirectErrorStream(true).start()
                try {
                    val line=readerPool.submit(Callable { child.inputStream.bufferedReader().readLine() }).get(20,TimeUnit.SECONDS)
                    assertEquals("DCT_READY",line,"child failed before the controlled commit boundary")
                    println("DCT crash operation=$operation boundary=$boundary childPid=${child.pid()}")
                    child.destroyForcibly();assertTrue(child.waitFor(10,TimeUnit.SECONDS))
                    val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
                    while(count("SELECT count(*) FROM pg_stat_activity WHERE application_name='$appName'")>0 && System.nanoTime()<deadline) Thread.onSpinWait()
                    assertEquals(0,count("SELECT count(*) FROM pg_stat_activity WHERE application_name='$appName'"))
                    val restarted=JdbcCashSessionStore(runtime()).list().filter { it.cashierId==f.staff.id.value }
                    when(operation) {
                        CashCrashOperation.OPEN -> {
                            assertEquals(if(boundary==CashCrashBoundary.BEFORE_COMMIT) 0 else 1,restarted.size)
                            assertEquals(if(boundary==CashCrashBoundary.BEFORE_COMMIT) 0 else 1,count("SELECT count(*) FROM audit_events WHERE actor_id=${f.staff.id.value}"))
                        }
                        CashCrashOperation.CLOSE -> {
                            assertEquals(if(boundary==CashCrashBoundary.BEFORE_COMMIT) CashSessionStatus.OPEN else CashSessionStatus.CLOSED,restarted.single().status)
                            assertEquals(if(boundary==CashCrashBoundary.BEFORE_COMMIT) 1 else 2,count("SELECT count(*) FROM audit_events WHERE actor_id=${f.staff.id.value}"))
                        }
                        CashCrashOperation.EXPENSE -> {
                            assertEquals(if(boundary==CashCrashBoundary.BEFORE_COMMIT) 0 else 1,count("SELECT count(*) FROM expenses WHERE cash_session_id=${cash!!.id}"))
                            assertEquals(if(boundary==CashCrashBoundary.BEFORE_COMMIT) 1 else 2,count("SELECT count(*) FROM audit_events WHERE actor_id=${f.staff.id.value}"))
                        }
                        CashCrashOperation.UNKNOWN -> fail<Unit>("unknown crash scenario")
                    }
                } finally { if(child.isAlive) { child.destroyForcibly();child.waitFor(10,TimeUnit.SECONDS) } }
            }
        } finally { readerPool.shutdownNow() }
    }
    companion object {
        private val native=System.getenv("DCT_TEST_JDBC_URL")
        private val container by lazy { PostgreSQLContainer<Nothing>("postgres:16-alpine").apply { start() } }
        private fun admin()=PGSimpleDataSource().apply { setURL(native ?: container.jdbcUrl);user=if(native != null) System.getenv("DCT_TEST_ADMIN") ?: "dct_admin" else container.username;password=if(native != null) "" else container.password }
        private fun runtime()=PGSimpleDataSource().apply { setURL(native ?: container.jdbcUrl);user="dct_runtime";password="dct_test_only" }
        @Synchronized private fun migrate() {
            val flyway=Flyway.configure().dataSource(admin()).locations("classpath:db/migration").load()
            flyway.migrate();flyway.validate()
            admin().connection.use { c ->
                c.prepareStatement("INSERT INTO roles(code) VALUES(?) ON CONFLICT DO NOTHING").use { s ->
                    StaffRole.entries.filter { it!=StaffRole.UNKNOWN }.forEach { s.setString(1,it.name);s.executeUpdate() }
                }
                c.createStatement().execute("DO ${'$'}${'$'} BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='dct_runtime') THEN CREATE ROLE dct_runtime LOGIN NOINHERIT PASSWORD 'dct_test_only'; END IF; END ${'$'}${'$'}")
                c.createStatement().execute("GRANT blackstore_app, blackstore_projection_worker TO dct_runtime")
            }
        }
    }
}
