package com.blackstore.infrastructure.persistence

import com.blackstore.domain.accounting.*
import com.blackstore.domain.cash.StaffRole
import com.blackstore.domain.identity.*
import com.blackstore.domain.port.out.accounting.AccountingMutationOutcome
import com.blackstore.domain.reports.*
import com.blackstore.domain.sales.PaymentMethod
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class AccountingReportPostgresTest {
    @Test fun mixedDayAndSessionFiltersUseIndependentUniverseAndCurrentAuthority() {
        val directUrl = System.getenv("BLACKSTORE_TEST_JDBC_URL")
        if (directUrl != null) {
            val source = PGSimpleDataSource().also { it.setURL(directUrl); it.user = System.getenv("BLACKSTORE_TEST_JDBC_USER"); it.password = System.getenv("BLACKSTORE_TEST_JDBC_PASSWORD") ?: "" }
            scenario(source)
        } else PostgreSQLContainer("postgres:16-alpine").use { db ->
            db.start()
            scenario(PGSimpleDataSource().also { it.setURL(db.jdbcUrl); it.user = db.username; it.password = db.password })
        }
    }
    private fun scenario(source: PGSimpleDataSource) {
            Flyway.configure().dataSource(source).load().migrate()
            fun sql(statement: String) = source.connection.use { c -> c.createStatement().use { it.execute(statement) } }
            sql("INSERT INTO roles(code) VALUES('CASHIER'),('OWNER')")
            val fixtureHash = org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode("report-test-fixture-only")
            sql("INSERT INTO staff_users(login,password_hash,role_code,display_name) VALUES('cashier','$fixtureHash','CASHIER','Cashier'),('owner','$fixtureHash','OWNER','Owner'),('cashier2','$fixtureHash','CASHIER','Second')")
            sql("INSERT INTO terminals(terminal_code) VALUES('OLD'),('NEW')")
            sql("INSERT INTO cash_session_projection(terminal_id,cashier_id,opened_at,opening_cash) VALUES(1,1,clock_timestamp()-interval '1 hour',100)")
            val legacySale = pendingSaleWithTwoLines(source, 1)
            for (status in listOf("PAYMENT_CAPTURED", "COMMIT_PENDING", "COMMITTED"))
                sql("UPDATE sale_state_projection SET status='$status' WHERE operation_id='${legacySale.operationId}'")
            sql("UPDATE accounting_runtime SET state='ACTIVE',accounting_activation_at=clock_timestamp()-interval '30 minutes' WHERE singleton")
            sql("INSERT INTO accounting_zone_versions(version,zone_id,effective_at) VALUES('v1','America/Argentina/Buenos_Aires','2000-01-01')")
            val owner = AuthenticatedStaff(StaffUserId(2), "Owner", StaffRole.OWNER)
            val commands = JdbcAccountingMutationCommands(source)
            val opened = commands.execute(owner, AccountingCommandDraft.CashSessionOpen(UUID.randomUUID(), 2, 2, BigDecimal.ZERO, "test opening"))
            // OWNER cannot be the holder: use another eligible cashier on the new terminal.
            assertTrue(opened is AccountingMutationOutcome.Rejected)
            val cashier = AuthenticatedStaff(StaffUserId(3), "Second", StaffRole.CASHIER)
            val receipt = (commands.execute(cashier, AccountingCommandDraft.CashSessionOpen(UUID.randomUUID(), 2, 3, BigDecimal.ZERO, null)) as AccountingMutationOutcome.Applied).receipt
            commands.execute(cashier, AccountingCommandDraft.ExpenseRecord(UUID.randomUUID(), receipt.cashSessionId, "paid operating expense", ExpenseInstruction.AccrueAndSettle("OPERATING", BigDecimal("7.00"), PaymentMethod.CARD)))
            val identity = pendingSaleWithTwoLines(source, receipt.cashSessionId)
            val capture = (commands.execute(cashier, AccountingCommandDraft.PaymentCapture(UUID.randomUUID(), identity,
                PaymentMethod.CASH, BigDecimal("10.00"), null)) as AccountingMutationOutcome.Applied).receipt
            assertTrue(commands.execute(cashier, AccountingCommandDraft.PaymentCapture(UUID.randomUUID(), identity,
                PaymentMethod.CARD, BigDecimal("20.00"), null)) is AccountingMutationOutcome.Applied)
            assertTrue(commands.execute(cashier, AccountingCommandDraft.PaymentReverse(UUID.randomUUID(), identity,
                requireNotNull(capture.paymentId), "reverse first component", "test reversal evidence")) is AccountingMutationOutcome.Applied)
            val query = JdbcAccountingReportQuery(source)
            val day = AccountingReportRequest(ReportPeriod.Day(LocalDate.now(ZoneId.of("America/Argentina/Buenos_Aires")), ZoneId.of("America/Argentina/Buenos_Aires")))
            val mixed = (query.read(owner, day) as AccountingReportResult.Available).report
            assertNotEquals(DataCompleteness.Complete, mixed.completeness.getValue(AccountingMetric.NetSales).state)
            assertEquals(BigDecimal("7.00"), mixed.totalsByMethod.getValue(PaymentMethod.CARD).expensesPaid)
            assertEquals(BigDecimal("20.00"), mixed.totalsByMethod.getValue(PaymentMethod.CARD).collected)
            assertEquals(BigDecimal("10.00"), mixed.totalsByMethod.getValue(PaymentMethod.CASH).collected)
            assertEquals(BigDecimal("10.00"), mixed.totalsByMethod.getValue(PaymentMethod.CASH).refunds)
            assertEquals(BigDecimal("0.00"), mixed.netSales)
            assertEquals("v1", mixed.zone.version)
            assertTrue(mixed.snapshot.isNotBlank())
            val previous = (query.read(owner, day.copy(period = (day.period as ReportPeriod.Day).copy(localDate = (day.period as ReportPeriod.Day).localDate.minusDays(1)))) as AccountingReportResult.Available).report
            assertEquals(BigDecimal("0.00"), previous.totalsByMethod.getValue(PaymentMethod.CARD).collected)
            assertFalse(previous.provisional)
            val missingDay = day.copy(filters = ReportFilters(cashSessionId = 999))
            assertEquals(AccountingReportFailure.NotVisible, (query.read(owner, missingDay) as AccountingReportResult.Rejected).failure)
            val shiftRequest = AccountingReportRequest(ReportPeriod.Shift(receipt.cashSessionId))
            val shift = (query.read(owner, shiftRequest) as AccountingReportResult.Available).report
            assertEquals(DataCompleteness.Complete, shift.completeness.getValue(AccountingMetric.NetSales).state)
            assertEquals(BigDecimal("0.00"), shift.reconciliation?.expectedCash)
            assertEquals(BigDecimal("0.00"), shift.netSales) // Pending sale lines do not recognize commercial sales.
            assertEquals(AccountingReportFailure.Forbidden, (query.read(cashier, shiftRequest) as AccountingReportResult.Rejected).failure)
            assertEquals(AccountingReportFailure.NotVisible, (query.read(owner, AccountingReportRequest(ReportPeriod.Shift(999))) as AccountingReportResult.Rejected).failure)
            sql("UPDATE staff_users SET active=false WHERE id=2")
            assertEquals(AccountingReportFailure.Forbidden, (query.read(owner, day) as AccountingReportResult.Rejected).failure)
    }
    private fun pendingSaleWithTwoLines(source: PGSimpleDataSource, cash: Long): com.blackstore.domain.model.OperationQuadruple {
        val identity = com.blackstore.domain.model.OperationQuadruple(UUID.randomUUID().toString(), "POS", "sale", UUID.randomUUID().toString())
        val contract = com.blackstore.domain.model.StoreCoreCanonicalContract
        source.connection.use { c ->
            c.autoCommit = false
            val writer = JdbcBlackStoreWriter()
            val id = writer.insertPendingSale(c, UUID.fromString(identity.clientInstanceId), identity.deviceId, identity.saleId,
                UUID.fromString(identity.operationId), cash, 3, contract.VERSION, contract.SHA256)
            writer.insertSaleLine(c, id, "SKU1", "First", 1, BigDecimal.TEN, BigDecimal.ZERO)
            writer.insertSaleLine(c, id, "SKU2", "Second", 1, BigDecimal("20.00"), BigDecimal.ZERO)
            c.createStatement().use { it.execute("UPDATE sale_state_projection SET status='RESERVED',gross_sales=30,storecore_reservation_ref='reservation-${identity.operationId}',reservation_receipt='receipt',contract_version='${contract.VERSION}',openapi_digest='${contract.SHA256}',accepted_price_versions='[\"price-v1\"]',reservation_expires_at=clock_timestamp()+interval '1 hour' WHERE id=$id") }
            val command = com.blackstore.domain.sales.OutboxCommand(identity, com.blackstore.domain.model.StoreCoreOperationKind.RESERVE,
                contract.CANONICAL_PATH, contract.VERSION, contract.SHA256, "b".repeat(64), payload =
                com.blackstore.domain.sales.CanonicalCommandPayload.Reserve("catalog-v1", listOf(
                    com.blackstore.domain.sales.CanonicalReserveLine("SKU1", 1, "price-v1"),
                    com.blackstore.domain.sales.CanonicalReserveLine("SKU2", 1, "price-v1"))))
            val mapper = DurableCommandMapper()
            val payload = mapper.encode(command)
            c.prepareStatement("INSERT INTO storecore_outbox_commands(client_instance_id,device_id,sale_id,operation_id,operation_kind,contract_version,openapi_digest,request_hash,payload_redacted,canonical_payload,payload_hash,actor_id,cash_session_id) VALUES(?,?,?,?,'RESERVE',?,?,?,'{}',?::jsonb,?,3,?) RETURNING id").use { s ->
                s.setObject(1, UUID.fromString(identity.clientInstanceId)); s.setString(2, identity.deviceId); s.setString(3, identity.saleId)
                s.setObject(4, UUID.fromString(identity.operationId)); s.setString(5, contract.VERSION); s.setString(6, contract.SHA256)
                s.setString(7, "b".repeat(64)); s.setString(8, payload); s.setString(9, mapper.hash(payload)); s.setLong(10, cash)
                s.executeQuery().use { r -> r.next(); c.createStatement().use { it.execute("INSERT INTO storecore_command_delivery(command_id,state) VALUES(${r.getLong(1)},'APPLIED')") } }
            }
            c.commit()
        }
        return identity
    }
}
