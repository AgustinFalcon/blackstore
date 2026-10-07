package com.blackstore.infrastructure.persistence

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/** Real PG16 checks: no in-memory dialect may accredit lifecycle or database privileges. */
class CashAccountingMigrationPostgresTest {
    @Test
    fun cleanMigrationStartsBeforeActivationAndRuntimeCannotActivateItself() = database { db ->
        migrate(db)
        connect(db).use { connection ->
            assertEquals("PRE_ACTIVATION", connection.scalar("SELECT state FROM accounting_runtime"))
            assertEquals("0", connection.scalar("SELECT count(*) FROM accounting_command_receipts"))
            for (role in listOf("blackstore_app", "blackstore_projection_worker", "blackstore_outbox_worker")) {
                connection.exec("SET ROLE $role")
                assertEquals("PRE_ACTIVATION", connection.scalar("SELECT state FROM accounting_runtime"))
                rejected(connection, "UPDATE accounting_runtime SET state='ACTIVE', accounting_activation_at=clock_timestamp()")
                rejected(connection, "DELETE FROM accounting_runtime")
                connection.exec("RESET ROLE")
            }
            activate(connection)
            val activation = connection.scalar("SELECT accounting_activation_at::text FROM accounting_runtime")
            connection.exec("SET ROLE blackstore_migration_owner")
            connection.exec("UPDATE accounting_runtime SET state='PAUSED'")
            rejected(connection, "UPDATE accounting_runtime SET state='PRE_ACTIVATION', accounting_activation_at=NULL")
            rejected(connection, "UPDATE accounting_runtime SET accounting_activation_at=clock_timestamp()")
            connection.exec("UPDATE accounting_runtime SET state='ACTIVE'")
            assertEquals(activation, connection.scalar("SELECT accounting_activation_at::text FROM accounting_runtime"))
            connection.exec("RESET ROLE")
        }
    }

    @Test
    fun populatedV7UpgradePreservesHistoryAndEnforcesSourcesAndAppendOnlyFacts() = database { db ->
        migrate(db, "7")
        connect(db).use { connection ->
            seedCash(connection)
            connection.exec("INSERT INTO cash_ledger_events(cash_session_id,event_type,amount_delta,actor_id) VALUES(1,'OPENING',10,1)")
            connection.exec("INSERT INTO expenses(cash_session_id,category,amount,reason,payment_method,accrued_at,created_by) VALUES(1,'OPERATING',5,'supplies','CASH',clock_timestamp(),1)")
        }
        migrate(db)
        connect(db).use { connection ->
            assertEquals("1", connection.scalar("SELECT count(*) FROM cash_ledger_events WHERE accounting_version IS NULL AND command_id IS NULL"))
            assertEquals("0", connection.scalar("SELECT count(*) FROM cash_accounting_coverage"))
            assertEquals("0", connection.scalar("SELECT count(*) FROM commercial_recognitions"))
            assertEquals("0", connection.scalar("SELECT count(*) FROM expense_settlements"))
            rejected(connection, receiptSql(1))
            activate(connection)
            connection.exec("SET ROLE blackstore_migration_owner")
            connection.exec("INSERT INTO cash_accounting_coverage(cash_session_id,coverage) VALUES(1,'LEGACY_INCOMPLETE')")
            connection.exec("RESET ROLE")
            connection.exec("SET ROLE blackstore_app")
            rejected(connection, "INSERT INTO cash_ledger_events(cash_session_id,event_type,amount_delta,actor_id) VALUES(1,'ADJUSTMENT',5,1)")
            connection.exec(receiptSql(1, "EXPENSE_RECORD"))
            rejected(connection, receiptSql(1)) // UUID, not amount, arbitrates replay.
            rejected(connection, "INSERT INTO expense_settlements(expense_id,cash_session_id,payment_method,amount,actor_id,command_id,paid_at) VALUES(1,1,'CASH',4,1,'${command(1)}',clock_timestamp())")
            val paidExpense = "INSERT INTO cash_ledger_events(cash_session_id,event_type,amount_delta,expense_id,actor_id,payment_method,command_id,origin_kind,origin_id,component,accounting_version,evidence_json,local_sequence,recorded_at) VALUES(1,'EXPENSE_PAID',-5,1,1,'CASH','${command(1)}','EXPENSE','1','EXPENSE_SETTLEMENT',2,'{}',1,clock_timestamp())"
            rejected(connection, paidExpense)
            connection.exec("INSERT INTO expense_settlements(expense_id,cash_session_id,payment_method,amount,actor_id,command_id,paid_at) VALUES(1,1,'CASH',5,1,'${command(1)}',clock_timestamp())")
            connection.exec(paidExpense)
            rejected(connection, "INSERT INTO expense_settlements(expense_id,cash_session_id,payment_method,amount,actor_id,command_id,paid_at) VALUES(1,1,'CASH',5,1,'${command(1)}',clock_timestamp())")
            assertEquals("1", connection.scalar("SELECT count(*) FROM expenses WHERE paid_at IS NULL"))
            rejected(connection, "UPDATE expense_settlements SET amount=4")
            rejected(connection, "DELETE FROM accounting_command_receipts")
            connection.exec(receiptSql(2, "CASH_SESSION_CLOSE"))
            connection.exec("SET ROLE blackstore_projection_worker")
            connection.exec("UPDATE cash_session_projection SET status='CLOSED',closed_at=clock_timestamp(),closing_cash_declared=10 WHERE id=1")
            connection.exec("SET ROLE blackstore_app")
            connection.exec("INSERT INTO cash_reconciliations(cash_session_id,command_id,actor_id,declared_cash,outcome,coverage,cutoff,local_watermark,accounting_version) SELECT 1,'${command(2)}',1,10,'UNAVAILABLE','LEGACY_INCOMPLETE',closed_at,1,2 FROM cash_session_projection WHERE id=1")
            rejected(connection, "INSERT INTO commercial_recognitions(sale_id,cash_session_id,command_id,actor_id,gross_sales,discounts,occurred_at,accounting_version) VALUES(999,1,'${command(1)}',1,10,0,clock_timestamp(),2)")
            connection.exec("RESET ROLE")
            // The owner cannot bypass append-only triggers either.
            rejected(connection, "UPDATE accounting_command_receipts SET payload_hash=repeat('b',64)")
            rejected(connection, "DELETE FROM expense_settlements")
            rejected(connection, "DELETE FROM cash_reconciliations")
            connection.exec("SET ROLE blackstore_projection_worker")
            rejected(connection, "INSERT INTO accounting_command_receipts SELECT * FROM accounting_command_receipts")
            rejected(connection, "UPDATE cash_ledger_events SET amount_delta=999")
            connection.exec("RESET ROLE")
        }
    }

    @Test
    fun openingRequiresActiveLifecycleAndCorrectSourceAndCoverage() = database { db ->
        migrate(db)
        connect(db).use { connection ->
            seedCash(connection)
            rejected(connection, openingSql())
            activate(connection)
            connection.exec("SET ROLE blackstore_app")
            connection.exec(receiptSql(1))
            connection.exec(receiptSql(2, "FEE_RECORD"))
            rejected(connection, openingSql().replace("'CASH_SESSION','1'", "'CASH_SESSION','2'"))
            rejected(connection, openingSql().replace("'CASH','", "'CARD','"))
            rejected(connection, "INSERT INTO cash_ledger_events(cash_session_id,event_type,amount_delta,actor_id,reason,evidence_ref,payment_method,command_id,origin_kind,origin_id,component,accounting_version,evidence_json,local_sequence,recorded_at) VALUES(1,'FEE',-1,1,'','','CASH','${command(2)}','OPERATION','${command(2)}','FEE_PAID',2,'{}',2,clock_timestamp())")
            connection.exec(openingSql())
            connection.exec("INSERT INTO cash_accounting_coverage(cash_session_id,coverage,command_id) VALUES(1,'COMPLETE_FROM_OPENING','${command(1)}')")
            rejected(connection, openingSql())
            rejected(connection, "INSERT INTO cash_reconciliations(cash_session_id,command_id,actor_id,declared_cash,expected_cash,outcome,coverage,cutoff,local_watermark,accounting_version) VALUES(1,'${command(1)}',1,10,10,'BALANCED','COMPLETE_FROM_OPENING',clock_timestamp(),1,2)")
            connection.exec("RESET ROLE")
            connection.exec("UPDATE accounting_runtime SET state='PAUSED'")
            connection.exec("SET ROLE blackstore_app")
            rejected(connection, receiptSql(2))
            assertEquals("2", connection.scalar("SELECT count(*) FROM accounting_command_receipts"))
        }
    }

    @Test
    fun refundsCompensateExactlyOneCapturedPaymentFromTheSameCashSession() = database { db ->
        migrate(db)
        connect(db).use { connection ->
            seedCash(connection)
            connection.exec("INSERT INTO sale_intents(client_instance_id,device_id,sale_id,operation_id,cash_session_id,created_by,contract_version,openapi_digest) VALUES('${command(10)}','POS','SALE','${command(11)}',1,1,'v1',repeat('a',64))")
            connection.exec("INSERT INTO sale_state_projection(sale_intent_id,client_instance_id,device_id,sale_id,operation_id,aggregate_operation_key) VALUES(1,'${command(10)}','POS','SALE','${command(11)}','${command(11)}')")
            connection.exec("INSERT INTO payments(sale_id,payment_method,amount,status) VALUES(1,'CASH',7,'CAPTURED')")
            rejected(connection, "INSERT INTO payments(sale_id,payment_method,amount,status,original_payment_id) VALUES(1,'CASH',7,'REFUNDED',1)")
            activate(connection)
            connection.exec("SET ROLE blackstore_app")
            connection.exec(receiptSql(1, "PAYMENT_CAPTURE", 1))
            connection.exec(receiptSql(3, "PAYMENT_CAPTURE", 1))
            connection.exec("INSERT INTO payments(sale_id,payment_method,amount,status,accounting_command_id,accounting_version) VALUES(1,'CASH',10,'CAPTURED','${command(1)}',2)")
            val capturePaymentId = connection.scalar("SELECT id::text FROM payments WHERE accounting_version=2 AND status='CAPTURED'")
            val capture = "INSERT INTO cash_ledger_events(cash_session_id,event_type,amount_delta,sale_id,payment_id,actor_id,payment_method,command_id,origin_kind,origin_id,component,accounting_version,evidence_json,local_sequence,recorded_at) VALUES(1,'PAYMENT',10,1,$capturePaymentId,1,'CASH','${command(1)}','PAYMENT','$capturePaymentId','PAYMENT_CAPTURE',2,'{}',1,clock_timestamp())"
            rejected(connection, capture.replace(command(1), command(3)))
            connection.exec(capture)
            connection.exec(receiptSql(2, "PAYMENT_REVERSE", 1))
            rejected(connection, "INSERT INTO payments(sale_id,payment_method,amount,status,original_payment_id,accounting_command_id,accounting_version) VALUES(1,'CASH',5,'REFUNDED',$capturePaymentId,'${command(2)}',2)")
            connection.exec("INSERT INTO payments(sale_id,payment_method,amount,status,original_payment_id,accounting_command_id,accounting_version) VALUES(1,'CASH',10,'REFUNDED',$capturePaymentId,'${command(2)}',2)")
            val refundPaymentId = connection.scalar("SELECT id::text FROM payments WHERE status='REFUNDED'")
            rejected(connection, "INSERT INTO payments(sale_id,payment_method,amount,status,original_payment_id,accounting_command_id,accounting_version) VALUES(1,'CASH',10,'REFUNDED',$capturePaymentId,'${command(2)}',2)")
            val captureEventId = connection.scalar("SELECT id::text FROM cash_ledger_events WHERE accounting_version=2 AND event_type='PAYMENT'")
            val refund = "INSERT INTO cash_ledger_events(cash_session_id,event_type,amount_delta,sale_id,payment_id,original_event_id,actor_id,payment_method,command_id,origin_kind,origin_id,component,accounting_version,evidence_json,local_sequence,recorded_at) VALUES(1,'REFUND',-10,1,$refundPaymentId,$captureEventId,1,'CASH','${command(2)}','PAYMENT','$refundPaymentId','PAYMENT_REFUND',2,'{}',2,clock_timestamp())"
            rejected(connection, refund.replace("'REFUND',-10", "'REFUND',-5"))
            rejected(connection, refund.replace("'PAYMENT','$refundPaymentId'", "'PAYMENT','1'"))
            connection.exec(refund)
            rejected(connection, refund)
            assertEquals("0.00", connection.scalar("SELECT sum(amount_delta)::text FROM cash_ledger_events WHERE accounting_version=2"))
        }
    }

    @Test
    fun duplicateHistoricalOpeningsFailPreflightWithoutDeletingEvidence() = database { db ->
        migrate(db, "7")
        connect(db).use { connection ->
            seedCash(connection)
            connection.exec("ALTER TABLE cash_ledger_events DISABLE TRIGGER cash_ledger_semantics")
            repeat(2) { connection.exec("INSERT INTO cash_ledger_events(cash_session_id,event_type,amount_delta,actor_id) VALUES(1,'OPENING',10,1)") }
            connection.exec("ALTER TABLE cash_ledger_events ENABLE TRIGGER cash_ledger_semantics")
        }
        val failure = assertThrows<Exception> { migrate(db) }
        assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it.message?.contains("duplicate historical OPENING") == true })
        connect(db).use { connection ->
            assertEquals("2", connection.scalar("SELECT count(*) FROM cash_ledger_events"))
            assertEquals("0", connection.scalar("SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tablename='accounting_runtime'"))
        }
    }

    private fun seedCash(connection: Connection) {
        connection.exec("INSERT INTO roles(code) VALUES('CASHIER')")
        connection.exec("INSERT INTO staff_users(login,password_hash,role_code) VALUES('cashier','\$2test','CASHIER')")
        connection.exec("INSERT INTO terminals(terminal_code) VALUES('POS')")
        connection.exec("INSERT INTO cash_session_projection(terminal_id,cashier_id,opened_at,opening_cash) VALUES(1,1,clock_timestamp(),10)")
    }

    private fun activate(connection: Connection) {
        connection.exec("UPDATE accounting_runtime SET state='ACTIVE', accounting_activation_at=clock_timestamp()")
    }

    private fun receiptSql(number: Int, kind: String = "CASH_SESSION_OPEN", saleId: Long? = null) = "INSERT INTO accounting_command_receipts(command_id,actor_id,command_kind,payload_hash,cash_session_id,sale_id,outcome,result,accounting_version) VALUES('${command(number)}',1,'$kind',repeat('a',64),1,${saleId ?: "NULL"},'COMMITTED','{}',2)"
    private fun openingSql() = "INSERT INTO cash_ledger_events(cash_session_id,event_type,amount_delta,actor_id,payment_method,command_id,origin_kind,origin_id,component,accounting_version,evidence_json,local_sequence,recorded_at) VALUES(1,'OPENING',10,1,'CASH','${command(1)}','CASH_SESSION','1','OPENING',2,'{}',1,clock_timestamp())"
    private fun command(number: Int) = "00000000-0000-0000-0000-${number.toString().padStart(12, '0')}"
    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }
    private fun Connection.scalar(sql: String): String = createStatement().use { statement -> statement.executeQuery(sql).use { rows -> rows.next(); rows.getString(1) } }
    private fun rejected(connection: Connection, sql: String) { assertThrows<SQLException> { connection.exec(sql) } }
    private fun connect(db: PostgreSQLContainer<*>) = DriverManager.getConnection(db.jdbcUrl, db.username, db.password)
    private fun migrate(db: PostgreSQLContainer<*>, target: String? = null) {
        val configuration = Flyway.configure().dataSource(db.jdbcUrl, db.username, db.password).locations("classpath:db/migration")
        if (target != null) configuration.target(target)
        configuration.load().migrate()
    }
    private fun database(test: (PostgreSQLContainer<*>) -> Unit) {
        val db: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
        db.use { it.start(); test(it) }
    }
}
