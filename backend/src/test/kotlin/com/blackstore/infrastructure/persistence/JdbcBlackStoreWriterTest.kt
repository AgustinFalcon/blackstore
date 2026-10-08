package com.blackstore.infrastructure.persistence

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.testcontainers.containers.PostgreSQLContainer
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import org.postgresql.ds.PGSimpleDataSource
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.sales.*

class JdbcBlackStoreWriterTest {

    private val writer = JdbcBlackStoreWriter()
    private val now = Instant.parse("2026-09-22T15:00:00Z")
    private lateinit var postgres: TestDatabase

    @Test
    fun appInsertsAutonomousRecordsAndCannotRewriteHistory() = database {
        migrate()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            val cashierId = seedReferenceData(connection)
            val terminalId = seedTerminal(connection)
            val digest = "a".repeat(64)
            val operationId = UUID.randomUUID()
            val clientId = UUID.randomUUID()

            val sessionId =
                asRole(connection, "blackstore_app") {
                    writer.insertOpenCashSession(connection, terminalId, cashierId, BigDecimal.ZERO, now)
                }
            val projectionId =
                asRole(connection, "blackstore_app") {
                    val id =
                        writer.insertPendingSale(
                            connection = connection,
                            clientInstanceId = clientId,
                            deviceId = "terminal-1",
                            saleId = "sale-1",
                            operationId = operationId,
                            cashSessionId = sessionId,
                            createdBy = cashierId,
                            contractVersion = "1.0.0-draft",
                            openapiDigest = digest,
                        )
                    val dataSource = PGSimpleDataSource().apply { setURL(postgres.jdbcUrl); user = postgres.username; password = postgres.password }
                    val identity=OperationQuadruple(clientId.toString(),"terminal-1","sale-1",operationId.toString())
                    val command=OutboxCommand(identity,com.blackstore.domain.model.StoreCoreOperationKind.RESERVE,"/blackstore-integration/v1","1.0.0-draft",digest,"b".repeat(64),payload=CanonicalCommandPayload.Reserve("catalog",listOf(CanonicalReserveLine("variant",1,"price-v1"))))
                    JdbcSaleRecordStore(dataSource).insertCanonicalCommand(connection,SaleSaga(identity,sessionId,createdBy=cashierId),command,cashierId,null)
                    writer.insertSaleLine(connection,id,"SKU","Cafe",1,BigDecimal("18.00"),BigDecimal.ZERO)
                    writer.insertAudit(connection, cashierId, "INTENT_CREATED", "sale", id)
                    val inserted = writer.insertInbox(connection, clientId, "terminal-1", "sale-1", operationId, "c".repeat(64), "1.0.0-draft", digest)
                    val duplicate = writer.insertInbox(connection, clientId, "terminal-1", "sale-1", operationId, "c".repeat(64), "1.0.0-draft", digest)
                    assertEquals(1, inserted)
                    assertEquals(0, duplicate)
                    id
                }
            asRole(connection, "blackstore_projection_worker") {
                writer.markReserved(connection, projectionId, "res-1", "rcpt-1", "1.0.0-draft", digest, now.plusSeconds(900))
            }
            val identity = OperationQuadruple(clientId.toString(), "terminal-1", "sale-1", operationId.toString())
            val other = identity.copy(deviceId = "terminal-2", saleId = "sale-2", operationId = UUID.randomUUID().toString())
            asRole(connection, "blackstore_app") {
                writer.insertPendingSale(connection, clientId, other.deviceId, other.saleId, UUID.fromString(other.operationId), sessionId, cashierId, "1.0.0-draft", digest)
                for (invalid in listOf("18.001", "0.001", "10.005", "7.995", "1000000000000")) {
                    assertThrows<IllegalArgumentException> { writer.insertPayment(connection, projectionId, "CASH", BigDecimal(invalid), BigDecimal.ZERO) }
                }
                assertThrows<IllegalArgumentException> { writer.insertPayment(connection, projectionId, "CASH", BigDecimal.ONE, BigDecimal("0.001")) }
                assertThrows<IllegalArgumentException> { writer.insertSaleLine(connection, projectionId, "SKU", "Cafe", 1, BigDecimal("18.001"), BigDecimal.ZERO) }
            }
            val dataSource = PGSimpleDataSource().apply { setURL(postgres.jdbcUrl); user = postgres.username; password = postgres.password }
            val ledger = JdbcCounterEntryStore(dataSource)
            connection.createStatement().execute("INSERT INTO roles(code) VALUES('SUPERVISOR') ON CONFLICT DO NOTHING")
            val supervisorId = connection.createStatement().executeQuery(
                "INSERT INTO staff_users(login,password_hash,role_code) VALUES('${UUID.randomUUID()}','${'$'}2test','SUPERVISOR') RETURNING id",
            ).use { rows -> rows.next(); rows.getLong(1) }
            val original = ledger.savePayment(
                PaymentBook().capture(1, PaymentMethod.CASH, BigDecimal("18.000"), BigDecimal.ZERO)
                    .copy(actorId = supervisorId, reason = "supervisor cross-cash authorization"),
                identity,
            )
            dataSource.connection.use { restarted ->
                restarted.prepareStatement(
                    "SELECT actor_id,payload_redacted->>'detail' FROM audit_events WHERE aggregate_type='payment' AND aggregate_id=? AND event_type='PAYMENT_CAPTURED'",
                ).use { statement ->
                    statement.setLong(1, original.id)
                    statement.executeQuery().use { rows ->
                        assertEquals(true, rows.next())
                        assertEquals(supervisorId, rows.getLong(1))
                        assertEquals("supervisor cross-cash authorization", rows.getString(2))
                        assertEquals(false, rows.next())
                    }
                }
            }
            assertEquals(1, (ledger.paymentLedger(identity) as OperationLedger.Known).entries.size)
            assertEquals(0, (ledger.paymentLedger(other) as OperationLedger.Known).entries.size)
            assertThrows<IllegalStateException> { ledger.savePayment(PaymentBook().capture(2, PaymentMethod.CASH, BigDecimal.ONE, BigDecimal.ZERO), operationId.toString()) }
            assertThrows<IllegalArgumentException> { ledger.paymentLedger(identity.copy(saleId = "missing")) }
            val reversal = PaymentBook().reverse(original, 3, cashierId, "return", "ev")
            assertThrows<IllegalArgumentException> { ledger.savePayment(reversal, other) }
            assertEquals(0, (ledger.paymentLedger(other) as OperationLedger.Known).entries.size)
            ledger.savePayment(reversal, identity)
            val history = (ledger.paymentLedger(identity) as OperationLedger.Known).entries
            assertEquals(2, history.size)
            assertEquals(PaymentStatus.REFUNDED, history.last().status)
            assertEquals(original.id, history.last().originalPaymentId)
            assertEquals(BigDecimal("18.00"), history.first().amount)
            connection.createStatement().execute("ALTER TABLE payments DROP CONSTRAINT payments_check")
            connection.prepareStatement("INSERT INTO payments (sale_id,payment_method,amount,fee_amount,status) SELECT id,'FUTURE',1,0,'FUTURE' FROM sale_state_projection WHERE device_id = ? AND operation_id = ?").use { statement ->
                statement.setString(1, other.deviceId)
                statement.setObject(2, UUID.fromString(other.operationId))
                statement.executeUpdate()
            }
            val corrupt = (ledger.paymentLedger(other) as OperationLedger.Known).entries.single()
            assertEquals(PaymentMethod.UNKNOWN, corrupt.method)
            assertEquals(PaymentStatus.UNKNOWN, corrupt.status)
            assertEquals(null, ledger.findPayment(corrupt.paymentId))
            asRole(connection, "blackstore_app") {
                assertThrows<SQLException> {
                    connection.createStatement().execute("UPDATE audit_events SET event_type = 'MUTATED'")
                }
            }

            asRole(connection,"blackstore_projection_worker") {
                writer.closeCashSession(connection,sessionId,BigDecimal("20.00"),now.plusSeconds(60))
            }
            connection.createStatement().execute("RESET ROLE")
            connection.createStatement().use { statement ->
                val status = statement.executeQuery("SELECT status FROM sale_state_projection WHERE id = $projectionId")
                status.next()
                assertEquals("RESERVED", status.getString(1))
                val cash = statement.executeQuery("SELECT status FROM cash_session_projection WHERE id = $sessionId")
                cash.next()
                assertEquals("CLOSED", cash.getString(1))
            }
        }
    }

    private fun migrate() {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }

    private fun seedReferenceData(connection: Connection): Long {
        connection.createStatement().execute(
            """
            INSERT INTO companion_installation (singleton, storecore_installation_ref, entitlement_state, environment, service_client_ref)
            VALUES (TRUE, '11111111-1111-1111-1111-111111111111', 'ENABLED', 'TEST', 'blackstore-service-local')
            """.trimIndent(),
        )
        connection.createStatement().execute("INSERT INTO roles (code) VALUES ('CASHIER')")
        connection.createStatement().executeQuery(
            """
            INSERT INTO staff_users (login, password_hash, role_code)
            VALUES ('cashier', '${'$'}2a${'$'}10${'$'}012345678901234567890u', 'CASHIER')
            RETURNING id
            """.trimIndent(),
        ).use { rows ->
            rows.next()
            return rows.getLong(1)
        }
    }

    private fun seedTerminal(connection: Connection): Long =
        connection.createStatement().executeQuery(
            "INSERT INTO terminals (terminal_code) VALUES ('T-1') RETURNING id",
        ).use { rows ->
            rows.next()
            rows.getLong(1)
        }

    private fun <T> asRole(connection: Connection, role: String, block: () -> T): T {
        connection.createStatement().execute("SET ROLE $role")
        try {
            return block()
        } finally {
            connection.createStatement().execute("RESET ROLE")
        }
    }

    private fun database(block: () -> Unit) {
        val direct = System.getenv("BLACKSTORE_TEST_JDBC_URL")
        if (!direct.isNullOrBlank()) {
            postgres = TestDatabase(direct, System.getenv("BLACKSTORE_TEST_JDBC_USER") ?: "postgres", System.getenv("BLACKSTORE_TEST_JDBC_PASSWORD") ?: "")
            block()
        } else PostgreSQLContainer("postgres:16-alpine").use {
            it.start()
            postgres = TestDatabase(it.jdbcUrl, it.username, it.password)
            block()
        }
    }
    private data class TestDatabase(val jdbcUrl: String, val username: String, val password: String)
}
