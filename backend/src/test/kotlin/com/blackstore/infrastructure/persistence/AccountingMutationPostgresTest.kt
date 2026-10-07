package com.blackstore.infrastructure.persistence

import com.blackstore.domain.accounting.*
import com.blackstore.domain.cash.StaffRole
import com.blackstore.domain.identity.*
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.port.out.accounting.*
import com.blackstore.domain.sales.*
import com.blackstore.domain.model.StoreCoreOperationKind
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.mockito.Mockito.mock
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/** These tests exercise V8's real triggers and the runtime roles in PostgreSQL 16. */
class AccountingMutationPostgresTest {
    private val staff = AuthenticatedStaff(StaffUserId(1), "Cashier", StaffRole.CASHIER)
    private val owner = AuthenticatedStaff(StaffUserId(2), "Owner", StaffRole.OWNER)

    @Test fun openingReplayMismatchAndLifecycleAreDurable() = database { source ->
        val commands = JdbcAccountingMutationCommands(source)
        val command = opening()
        assertEquals(AccountingCommandFailure.NotActivated, rejected(commands.execute(staff, command)))
        activate(source)
        val first = applied(commands.execute(staff, command))
        assertEquals(first, applied(commands.execute(staff, command)))
        assertEquals(AccountingCommandFailure.PayloadMismatch, rejected(commands.execute(staff, command.copy(openingCash = BigDecimal.ONE))))
        assertEquals(AccountingCommandFailure.CashSessionConflict, rejected(commands.execute(staff, command.copy(commandId = UUID.randomUUID()))))
        val otherCashier = AuthenticatedStaff(StaffUserId(3), "Other", StaffRole.CASHIER)
        assertEquals(AccountingCommandFailure.NotVisible, rejected(commands.execute(otherCashier,
            command.copy(commandId = UUID.randomUUID(), cashierId = otherCashier.id.value))))
        assertEquals(AccountingCommandFailure.NotVisible, rejected(commands.execute(owner,
            command.copy(commandId = UUID.randomUUID(), cashierId = 999, reason = "owner override"))))
        assertEquals("1", scalar(source, "SELECT count(*) FROM cash_accounting_coverage WHERE coverage='COMPLETE_FROM_OPENING'"))
        assertEquals("1", scalar(source, "SELECT count(*) FROM cash_ledger_events WHERE event_type='OPENING' AND amount_delta=0"))
        sql(source, "UPDATE accounting_runtime SET state='PAUSED'")
        assertEquals(first, applied(commands.execute(staff, command)))
        assertEquals(AccountingCommandFailure.Paused, rejected(commands.execute(staff,
            AccountingCommandDraft.ExpenseRecord(UUID.randomUUID(), first.cashSessionId, "supplies", ExpenseInstruction.Accrue("OPERATING", BigDecimal.ONE)))))
        assertEquals(AccountingCommandResult.Committed(first), JdbcAccountingMutationCommands(source).findReceipt(staff, first.commandId))
    }

    @Test fun splitCaptureFullRefundAndRemainingBalanceKeepStructuredEvidence() = database { source ->
        activate(source)
        val commands = JdbcAccountingMutationCommands(source)
        val cash = applied(commands.execute(staff, opening())).cashSessionId
        val identity = sale(source, cash)
        val capture = AccountingCommandDraft.PaymentCapture(UUID.randomUUID(), identity, PaymentMethod.CASH, BigDecimal("4.00"))
        val payment = applied(commands.execute(staff, capture))
        val second = applied(commands.execute(staff, capture.copy(commandId = UUID.randomUUID(), amount = BigDecimal("6.00"))))
        val reverse = AccountingCommandDraft.PaymentReverse(UUID.randomUUID(), identity, payment.paymentId!!, "refund requested", "receipt-123")
        val refunded = applied(commands.execute(staff, reverse))
        assertEquals(refunded, applied(commands.execute(staff, reverse)))
        assertEquals(AccountingCommandFailure.TransitionConflict, rejected(commands.execute(staff, reverse.copy(commandId = UUID.randomUUID()))))
        assertNotNull(second.paymentId)
        assertEquals("6.00", scalar(source, "SELECT sum(amount_delta)::text FROM cash_ledger_events WHERE event_type IN ('PAYMENT','REFUND')"))
        assertEquals("1", scalar(source, "SELECT count(*) FROM payments WHERE status='REFUNDED' AND original_payment_id=${payment.paymentId}"))
        assertEquals("1", scalar(source, "SELECT count(*) FROM cash_ledger_events WHERE event_type='REFUND' AND original_event_id=${payment.ledgerEventIds.single()}"))
        applied(commands.execute(staff, capture.copy(commandId = UUID.randomUUID(), amount = BigDecimal("4.00"))))
        assertEquals("PAYMENT_CAPTURED", scalar(source, "SELECT status FROM sale_state_projection"))
        sql(source, "UPDATE cash_session_projection SET status='CLOSED',closed_at=clock_timestamp(),closing_cash_declared=10 WHERE id=$cash")
        assertEquals(payment, applied(commands.execute(staff, capture)))
        assertEquals(AccountingCommandFailure.Closed, rejected(commands.execute(staff, capture.copy(commandId = UUID.randomUUID(), amount = BigDecimal.ONE))))
    }

    @Test fun fullyRefundedV2SaleCanEnterDurableReleasePending() = database { source ->
        activate(source)
        val commands = JdbcAccountingMutationCommands(source)
        val cash = applied(commands.execute(staff, opening())).cashSessionId
        val identity = sale(source, cash)
        val capture = applied(commands.execute(staff, AccountingCommandDraft.PaymentCapture(UUID.randomUUID(), identity, PaymentMethod.CASH, BigDecimal.TEN)))
        applied(commands.execute(staff, AccountingCommandDraft.PaymentReverse(UUID.randomUUID(), identity, capture.paymentId!!, "full refund", "refund-proof")))
        val current = requireNotNull(JdbcDurableSaleRepository(source).findDurable(identity.operationId)).saga
        val evidence = requireNotNull(current.evidence)
        val release = OutboxCommand(identity, StoreCoreOperationKind.RELEASE, "/blackstore-integration/v1", evidence.contractVersion,
            evidence.openapiDigest, "c".repeat(64), reservationRef = evidence.reservationRef,
            payload = CanonicalCommandPayload.Terminal(evidence.reservationRef))
        val pending = current.copy(status = SaleStatus.RELEASE_PENDING, outbox = current.outbox + release,
            staffCommandAudit = SaleStaffCommandAudit(SaleStaffCommandEvent.RELEASE_REQUESTED, staff.id, null))
        JdbcSaleRecordStore(source).recordReleasePending(pending)
        assertEquals("RELEASE_PENDING", scalar(source, "SELECT status FROM sale_state_projection"))
        assertEquals("1", scalar(source, "SELECT count(*) FROM storecore_outbox_commands WHERE operation_kind='RELEASE'"))
    }

    @Test fun fullyRefundedV2SaleReleasesThroughRealServiceAndCounterLedger() = database { source ->
        activate(source)
        val commands = JdbcAccountingMutationCommands(source)
        val cash = applied(commands.execute(staff, opening())).cashSessionId
        val identity = sale(source, cash)
        val capture = applied(commands.execute(staff,
            AccountingCommandDraft.PaymentCapture(UUID.randomUUID(), identity, PaymentMethod.CASH, BigDecimal.TEN)))
        applied(commands.execute(staff,
            AccountingCommandDraft.PaymentReverse(UUID.randomUUID(), identity, capture.paymentId!!, "full refund", "refund-proof")))

        val staffIdentity = com.blackstore.infrastructure.identity.JdbcStaffIdentity(
            source,
            com.blackstore.infrastructure.identity.SecureSessionTokenGenerator(),
        )

        val inventory = object : com.blackstore.domain.port.out.storecore.StoreCoreInventoryPort {
            override fun reserve(command: com.blackstore.domain.port.out.storecore.ReserveInventoryCommand) = error("not used")
            override fun commit(command: com.blackstore.domain.port.out.storecore.CommitInventoryCommand) = error("not used")
            override fun release(command: com.blackstore.domain.port.out.storecore.ReleaseInventoryCommand) =
                com.blackstore.domain.model.StoreCoreOperationReceipt(
                    command.quadruple,
                    StoreCoreOperationKind.RELEASE,
                    com.blackstore.domain.model.StoreCoreOperationState.RELEASED,
                    command.reservationRef,
                    "release-receipt",
                    com.blackstore.domain.model.StoreCoreContractRef(
                        com.blackstore.domain.model.StoreCoreCanonicalContract.CANONICAL_PATH,
                        com.blackstore.domain.model.StoreCoreCanonicalContract.VERSION,
                        com.blackstore.domain.model.StoreCoreCanonicalContract.SHA256,
                    ),
                    listOf("price-v1"),
                    null,
                )
            override fun getOperation(quadruple: OperationQuadruple) = null
        }
        val service = com.blackstore.application.sales.LocalSaleSagaService(
            catalogPort = mock(com.blackstore.domain.port.out.storecore.StoreCoreCatalogPort::class.java),
            inventoryPort = inventory,
            retirementPort = mock(com.blackstore.domain.port.out.storecore.OperationRetirementPort::class.java),
            saleRecordStore = JdbcSaleRecordStore(source),
            canonicalPath = com.blackstore.domain.model.StoreCoreCanonicalContract.CANONICAL_PATH,
            contractVersion = com.blackstore.domain.model.StoreCoreCanonicalContract.VERSION,
            openapiDigest = com.blackstore.domain.model.StoreCoreCanonicalContract.SHA256,
            counterEntryStore = JdbcCounterEntryStore(source),
            authorization = com.blackstore.application.identity.AuthorizeStaffAction(staffIdentity, staffIdentity),
            persistenceEnabled = true,
            accountingRuntime = JdbcAccountingRuntimeQuery(source),
        )

        val activeView = requireNotNull(service.detail(staff, identity.operationId))
        assertEquals(PaymentCoverage.Unpaid, activeView.snapshot.paymentCoverage)
        assertTrue(activeView.allowedActions.contains(SaleAllowedAction.RELEASE))
        assertTrue(activeView.allowedActions.contains(SaleAllowedAction.CAPTURE_PAYMENT))
        sql(source, "UPDATE accounting_runtime SET state='PAUSED'")
        val pausedView = requireNotNull(service.detail(staff, identity.operationId))
        assertEquals(activeView.snapshot, pausedView.snapshot)
        assertEquals(activeView.allowedActions, pausedView.allowedActions)
        sql(source, "UPDATE accounting_runtime SET state='ACTIVE'")

        val released = service.release(staff, identity.operationId, null)
        assertEquals(SaleStatus.RELEASED, released.status)
        assertEquals("RELEASED", scalar(source, "SELECT status FROM sale_state_projection"))
        assertEquals("1", scalar(source, "SELECT count(*) FROM storecore_outbox_commands WHERE operation_kind='RELEASE'"))
    }

    @Test fun accrualAndOneFullSettlementNeverRewriteExpenseHistory() = database { source ->
        activate(source)
        val commands = JdbcAccountingMutationCommands(source)
        val cash = applied(commands.execute(staff, opening())).cashSessionId
        val accrue = AccountingCommandDraft.ExpenseRecord(UUID.randomUUID(), cash, "rent invoice", ExpenseInstruction.Accrue("OPERATING", BigDecimal("5.00")))
        val accrued = applied(commands.execute(staff, accrue))
        assertEquals("0", scalar(source, "SELECT count(*) FROM expense_settlements"))
        assertEquals("0.00", scalar(source, "SELECT coalesce(sum(amount_delta) FILTER(WHERE event_type='EXPENSE_PAID'),0)::numeric(14,2)::text FROM cash_ledger_events"))
        val settle = AccountingCommandDraft.ExpenseRecord(UUID.randomUUID(), cash, "rent payment", ExpenseInstruction.SettleExisting(accrued.expenseId!!, PaymentMethod.TRANSFER))
        val settled = applied(commands.execute(staff, settle))
        assertNotNull(settled.settlementId)
        assertEquals(settled, applied(commands.execute(staff, settle)))
        assertEquals(AccountingCommandFailure.Validation, rejected(commands.execute(staff, settle.copy(commandId = UUID.randomUUID()))))
        applied(commands.execute(staff, AccountingCommandDraft.ExpenseRecord(UUID.randomUUID(), cash, "paid supplies", ExpenseInstruction.AccrueAndSettle("OPERATING", BigDecimal("2.00"), PaymentMethod.CASH))))
        assertEquals("2", scalar(source, "SELECT count(*) FROM expenses WHERE paid_at IS NULL"))
        assertEquals("2", scalar(source, "SELECT count(*) FROM expense_settlements"))
        assertEquals("-7.00", scalar(source, "SELECT sum(amount_delta)::text FROM cash_ledger_events WHERE event_type='EXPENSE_PAID'"))
    }

    @Test fun feeUsesOwnerPortAndExplicitPaidMethod() = database { source ->
        activate(source)
        val commands = JdbcAccountingMutationCommands(source)
        val cash = applied(commands.execute(staff, opening())).cashSessionId
        val fee = PaidFeeCommand(UUID.randomUUID(), cash, PaymentMethod.CARD, BigDecimal("1.00"), "provider fee", "provider-receipt")
        assertEquals(AccountingCommandFailure.Forbidden, rejected(commands.recordFee(staff, fee)))
        val first = applied(commands.recordFee(owner, fee))
        assertEquals(first, applied(commands.recordFee(owner, fee)))
        assertEquals(AccountingCommandResult.NotFound, commands.findReceipt(owner, fee.commandId))
        assertEquals("CARD", scalar(source, "SELECT payment_method FROM cash_ledger_events WHERE event_type='FEE'"))
        assertEquals("-1.00", scalar(source, "SELECT amount_delta::text FROM cash_ledger_events WHERE event_type='FEE'"))
    }

    @Test fun auditFailureRollsBackReceiptPaymentPostingAndProjection() = database { source ->
        activate(source)
        val commands = JdbcAccountingMutationCommands(source)
        val cash = applied(commands.execute(staff, opening())).cashSessionId
        val identity = sale(source, cash)
        val command = AccountingCommandDraft.PaymentCapture(UUID.randomUUID(), identity, PaymentMethod.CASH, BigDecimal("10.00"))
        val failing = JdbcAccountingMutationCommands(FailingAuditSource(source))
        assertEquals(AccountingCommandFailure.Unavailable, rejected(failing.execute(staff, command)))
        assertEquals("0", scalar(source, "SELECT count(*) FROM payments"))
        assertEquals("0", scalar(source, "SELECT count(*) FROM accounting_command_receipts WHERE command_id='${command.commandId}'"))
        assertEquals("0", scalar(source, "SELECT count(*) FROM cash_ledger_events WHERE event_type='PAYMENT'"))
        assertEquals("RESERVED", scalar(source, "SELECT status FROM sale_state_projection"))
        assertEquals(AccountingCommandResult.NotFound, commands.findReceipt(staff, command.commandId))
        applied(commands.execute(staff, command))
    }

    @Test fun concurrentSameCommandCommitsOneCaptureAndMismatchedPayloadHasOneWinner() = database { source ->
        activate(source)
        val commands = JdbcAccountingMutationCommands(source)
        val cash = applied(commands.execute(staff, opening())).cashSessionId
        val identity = sale(source, cash)
        val command = AccountingCommandDraft.PaymentCapture(UUID.randomUUID(), identity, PaymentMethod.CASH, BigDecimal("4.00"))
        val pool = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val a = pool.submit<AccountingMutationOutcome> { start.await(); commands.execute(staff, command) }
            val b = pool.submit<AccountingMutationOutcome> { start.await(); commands.execute(staff, command) }
            start.countDown()
            assertEquals(applied(a.get(15, TimeUnit.SECONDS)), applied(b.get(15, TimeUnit.SECONDS)))
            assertEquals("1", scalar(source, "SELECT count(*) FROM payments"))
            val next = command.copy(commandId = UUID.randomUUID(), amount = BigDecimal("2.00"))
            val secondStart = CountDownLatch(1)
            val x = pool.submit<AccountingMutationOutcome> { secondStart.await(); commands.execute(staff, next) }
            val y = pool.submit<AccountingMutationOutcome> { secondStart.await(); commands.execute(staff, next.copy(amount = BigDecimal("3.00"))) }
            secondStart.countDown()
            val outcomes = listOf(x.get(15, TimeUnit.SECONDS), y.get(15, TimeUnit.SECONDS))
            assertEquals(1, outcomes.count { it is AccountingMutationOutcome.Applied })
            assertEquals(AccountingCommandFailure.PayloadMismatch, rejected(outcomes.single { it is AccountingMutationOutcome.Rejected }))
            assertEquals("2", scalar(source, "SELECT count(*) FROM payments"))
        } finally { pool.shutdownNow() }
    }

    private fun opening() = AccountingCommandDraft.CashSessionOpen(UUID.randomUUID(), 1, 1, BigDecimal("0.00"), null)
    private fun applied(outcome: AccountingMutationOutcome) = (outcome as AccountingMutationOutcome.Applied).receipt
    private fun rejected(outcome: AccountingMutationOutcome) = (outcome as AccountingMutationOutcome.Rejected).failure
    private fun activate(source: DataSource) = sql(source, "UPDATE accounting_runtime SET state='ACTIVE', accounting_activation_at=clock_timestamp()")
    private fun sale(source: DataSource, cash: Long): OperationQuadruple {
        val identity = OperationQuadruple(UUID.randomUUID().toString(), "POS", "sale", UUID.randomUUID().toString())
        val contractVersion = com.blackstore.domain.model.StoreCoreCanonicalContract.VERSION
        val openapiDigest = com.blackstore.domain.model.StoreCoreCanonicalContract.SHA256
        val writer = JdbcBlackStoreWriter()
        source.connection.use { c ->
            c.autoCommit = false
            val id = writer.insertPendingSale(c, UUID.fromString(identity.clientInstanceId), identity.deviceId, identity.saleId,
                UUID.fromString(identity.operationId), cash, 1, contractVersion, openapiDigest)
            writer.insertSaleLine(c, id, "SKU", "Product", 1, BigDecimal.TEN, BigDecimal.ZERO)
            c.createStatement().execute("UPDATE sale_state_projection SET status='RESERVED',storecore_reservation_ref='reservation',reservation_receipt='receipt',contract_version='$contractVersion',openapi_digest='$openapiDigest',accepted_price_versions='[\"price-v1\"]',reservation_expires_at=clock_timestamp()+interval '1 hour' WHERE id=$id")
            // A real canonical outbox is needed for durable read to classify the sale, rather than guessing legacy state.
            val command = com.blackstore.domain.sales.OutboxCommand(identity, com.blackstore.domain.model.StoreCoreOperationKind.RESERVE,
                com.blackstore.domain.model.StoreCoreCanonicalContract.CANONICAL_PATH, contractVersion, openapiDigest, "b".repeat(64), payload =
                com.blackstore.domain.sales.CanonicalCommandPayload.Reserve("catalog-v1", listOf(
                    com.blackstore.domain.sales.CanonicalReserveLine("SKU", 1, "price-v1"))))
            val mapper = DurableCommandMapper()
            val payload = mapper.encode(command)
            c.prepareStatement("INSERT INTO storecore_outbox_commands(client_instance_id,device_id,sale_id,operation_id,operation_kind,contract_version,openapi_digest,request_hash,payload_redacted,canonical_payload,payload_hash,actor_id,cash_session_id) VALUES(?,?,?,?,'RESERVE',?, ?,?,'{}',?::jsonb,?,1,?) RETURNING id").use { s ->
                s.setObject(1, UUID.fromString(identity.clientInstanceId)); s.setString(2, identity.deviceId); s.setString(3, identity.saleId); s.setObject(4, UUID.fromString(identity.operationId))
                s.setString(5, contractVersion); s.setString(6, openapiDigest); s.setString(7, "b".repeat(64)); s.setString(8, payload); s.setString(9, mapper.hash(payload)); s.setLong(10, cash)
                s.executeQuery().use { r -> r.next(); c.createStatement().execute("INSERT INTO storecore_command_delivery(command_id,state) VALUES(${r.getLong(1)},'APPLIED')") }
            }
            c.commit()
        }
        return identity
    }

    private fun scalar(source: DataSource, sql: String): String = source.connection.use { c -> c.createStatement().use { s -> s.executeQuery(sql).use { r -> r.next(); r.getString(1) } } }
    private fun sql(source: DataSource, sql: String) { source.connection.use { c -> c.createStatement().use { it.execute(sql) } } }
    private fun database(block: (DataSource) -> Unit) {
        val directUrl = System.getenv("BLACKSTORE_TEST_JDBC_URL")
        if (!directUrl.isNullOrBlank()) {
            val source = PGSimpleDataSource().also { dataSource ->
                dataSource.setURL(directUrl)
                dataSource.user = System.getenv("BLACKSTORE_TEST_JDBC_USER") ?: "postgres"
                dataSource.password = System.getenv("BLACKSTORE_TEST_JDBC_PASSWORD") ?: ""
            }
            val flyway = Flyway.configure().cleanDisabled(false).dataSource(source).locations("classpath:db/migration").load()
            flyway.clean()
            sql(source, "DO \$\$ DECLARE role_name text; BEGIN FOR role_name IN SELECT rolname FROM pg_roles WHERE rolname IN " +
                "('blackstore_app','blackstore_projection_worker','blackstore_outbox_worker','blackstore_auditor','blackstore_migration_owner','blackstore_staff_provisioner') " +
                "LOOP EXECUTE format('DROP OWNED BY %I CASCADE', role_name); EXECUTE format('DROP ROLE %I', role_name); END LOOP; END \$\$")
            flyway.migrate()
            seed(source)
            block(source)
            return
        }
        val db: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
        db.use {
            it.start()
            val source = PGSimpleDataSource().also { source -> source.setURL(it.jdbcUrl); source.user = it.username; source.password = it.password }
            Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate()
            seed(source)
            block(source)
        }
    }

    private fun seed(source: DataSource) {
        sql(source, "INSERT INTO roles(code) VALUES('CASHIER'),('OWNER')")
        sql(source, "INSERT INTO staff_users(login,password_hash,role_code,display_name) VALUES('cashier','\$2test','CASHIER','Cashier'),('owner','\$2test','OWNER','Owner'),('cashier2','\$2test','CASHIER','Other')")
        sql(source, "INSERT INTO terminals(terminal_code) VALUES('POS')")
    }

    private class FailingAuditSource(private val delegate: DataSource) : DataSource by delegate {
        override fun getConnection(): Connection {
            val connection = delegate.connection
            return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, arguments ->
                if (method.name == "prepareStatement" && (arguments?.firstOrNull() as? String)?.trimStart()?.startsWith("INSERT INTO audit_events") == true)
                    throw SQLException("test failure at final audit")
                try { method.invoke(connection, *(arguments ?: emptyArray())) }
                catch (error: InvocationTargetException) { throw error.targetException }
            } as Connection
        }
    }
}
