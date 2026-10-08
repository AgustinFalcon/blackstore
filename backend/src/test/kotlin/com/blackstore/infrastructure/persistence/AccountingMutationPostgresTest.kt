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
import java.net.URI
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

/** These tests exercise V8's real triggers and the runtime roles in PostgreSQL 16. */
class AccountingMutationPostgresTest {
    private val staff = AuthenticatedStaff(StaffUserId(1), "Cashier", StaffRole.CASHIER)
    private val owner = AuthenticatedStaff(StaffUserId(2), "Owner", StaffRole.OWNER)

    @Test fun crashHarnessClosedTypesTranslateKnownAndUnknownWireValues() {
        AccountingCrashBoundary.entries.filter { it != AccountingCrashBoundary.Unknown }.forEach {
            assertEquals(it, AccountingCrashBoundary.fromWire(it.name))
        }
        AccountingCrashOperation.entries.filter { it != AccountingCrashOperation.Unknown }.forEach {
            assertEquals(it, AccountingCrashOperation.fromWire(it.name))
        }
        assertEquals(AccountingCrashBoundary.Unknown, AccountingCrashBoundary.fromWire(null))
        assertEquals(AccountingCrashBoundary.Unknown, AccountingCrashBoundary.fromWire("AFTER_COMMIT"))
        assertEquals(AccountingCrashOperation.Unknown, AccountingCrashOperation.fromWire(null))
        assertEquals(AccountingCrashOperation.Unknown, AccountingCrashOperation.fromWire("REFUND"))
    }

    @Test fun closePersistsSignedSnapshotAndReplayWithoutAdjustment() = database { source ->
        activate(source)
        val commands = JdbcAccountingMutationCommands(source)
        for ((declared, expectedOutcome) in listOf("10" to ReconciliationOutcome.Balanced,
            "8" to ReconciliationOutcome.Shortage, "12" to ReconciliationOutcome.Overage)) {
            val cash = applied(commands.execute(staff, opening().copy(openingCash = BigDecimal.TEN))).cashSessionId
            val command = AccountingCommandDraft.CashSessionClose(UUID.randomUUID(), cash, BigDecimal(declared), "close shift")
            val first = applied(commands.execute(staff, command))
            val snapshot = requireNotNull(first.closeSnapshot)
            assertEquals(expectedOutcome, snapshot.outcome)
            assertEquals(BigDecimal("10.00"), snapshot.expectedCash)
            assertEquals(BigDecimal(declared).setScale(2) - BigDecimal("10.00"), snapshot.difference)
            assertEquals(1L, snapshot.localWatermark)
            assertEquals(first, applied(commands.execute(staff, command)))
            assertEquals(AccountingCommandResult.Committed(first), commands.findReceipt(staff, first.commandId))
            assertEquals(AccountingCommandFailure.PayloadMismatch, rejected(commands.execute(staff, command.copy(declaredCash = BigDecimal("9")))))
            assertEquals("1", scalar(source, "SELECT count(*) FROM cash_reconciliations WHERE cash_session_id=$cash"))
            assertEquals("1", scalar(source, "SELECT count(*) FROM cash_ledger_events WHERE cash_session_id=$cash"))
            assertEquals("true", scalar(source, "SELECT bool_and(l.occurred_at<c.closed_at)::text FROM cash_ledger_events l JOIN cash_session_projection c ON c.id=l.cash_session_id WHERE c.id=$cash"))
        }
    }

    @Test fun pendingSalesAndForeignSessionsRejectBeforeReconciliation() = database { source ->
        activate(source)
        val commands = JdbcAccountingMutationCommands(source)
        val cash = applied(commands.execute(staff, opening())).cashSessionId
        sale(source, cash)
        val command = AccountingCommandDraft.CashSessionClose(UUID.randomUUID(), cash, BigDecimal.ZERO, "close shift")
        val other = AuthenticatedStaff(StaffUserId(3), "Other", StaffRole.CASHIER)
        assertEquals(AccountingCommandFailure.NotVisible, rejected(commands.execute(other, command)))
        assertEquals(AccountingCommandFailure.NonTerminalSale, rejected(commands.execute(staff, command)))
        assertEquals("OPEN", scalar(source, "SELECT status FROM cash_session_projection"))
        assertEquals("0", scalar(source, "SELECT count(*) FROM cash_reconciliations"))
    }

    @Test fun legacyClosePreservesNullOfficialAmountsAndAuditFailureRollsBack() = database { source ->
        sql(source, "INSERT INTO cash_session_projection(terminal_id,cashier_id,opened_at,opening_cash) VALUES(1,1,clock_timestamp(),20)")
        activate(source)
        val commands = JdbcAccountingMutationCommands(source)
        val command = AccountingCommandDraft.CashSessionClose(UUID.randomUUID(), 1, BigDecimal("18"), "close legacy shift")
        assertEquals(AccountingCommandFailure.Unavailable, rejected(JdbcAccountingMutationCommands(FailingAuditSource(source)).execute(staff, command)))
        assertEquals("OPEN", scalar(source, "SELECT status FROM cash_session_projection"))
        assertEquals("0", scalar(source, "SELECT count(*) FROM cash_accounting_coverage"))
        assertEquals("0", scalar(source, "SELECT count(*) FROM cash_reconciliations"))
        val snapshot = requireNotNull(applied(commands.execute(staff, command)).closeSnapshot)
        assertEquals(ReconciliationOutcome.Unavailable, snapshot.outcome)
        assertEquals(AccountingCoverage.LegacyIncomplete, snapshot.coverage)
        assertNull(snapshot.expectedCash); assertNull(snapshot.difference)
        assertEquals("0", scalar(source, "SELECT count(*) FROM cash_ledger_events"))
    }

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

    @Test fun v2ChildCrashBeforeAndAfterCommitRecoversByReceiptWithoutDuplication() = database { source ->
        activate(source)
        val commands = JdbcAccountingMutationCommands(source)
        val jdbcUrl = source.connection.use { it.metaData.url }
        require(URI(jdbcUrl.removePrefix("jdbc:")).host in setOf("localhost", "127.0.0.1", "::1")) {
            "crash harness requires an isolated loopback PostgreSQL"
        }
        val role = "clr_crash_${UUID.randomUUID().toString().replace("-", "")}"
        val password = UUID.randomUUID().toString()
        sql(source, "CREATE ROLE $role LOGIN NOINHERIT PASSWORD '$password'")
        sql(source, "GRANT blackstore_app, blackstore_projection_worker TO $role")
        val reader = Executors.newSingleThreadExecutor()
        try {
            for (operation in AccountingCrashOperation.entries.filter {
                it != AccountingCrashOperation.Unknown && it != AccountingCrashOperation.Recognition }) {
                for (boundary in AccountingCrashBoundary.entries.filter { it != AccountingCrashBoundary.Unknown }) {
                    val cash = if (operation == AccountingCrashOperation.Open) null
                    else applied(commands.execute(staff, opening().copy(commandId = UUID.randomUUID()))).cashSessionId
                    val identity = if (operation in setOf(AccountingCrashOperation.PaymentCapture, AccountingCrashOperation.PaymentReverse))
                        sale(source, requireNotNull(cash)) else null
                    val originalPayment = if (operation == AccountingCrashOperation.PaymentReverse)
                        applied(commands.execute(staff, AccountingCommandDraft.PaymentCapture(
                            UUID.randomUUID(), requireNotNull(identity), PaymentMethod.CASH, BigDecimal("10.00")))).paymentId else null
                    val commandId = UUID.randomUUID()
                    val command = when (operation) {
                        AccountingCrashOperation.Open -> AccountingCommandDraft.CashSessionOpen(commandId, 1, 1, BigDecimal.ZERO, "crash harness opening")
                        AccountingCrashOperation.Expense -> AccountingCommandDraft.ExpenseRecord(commandId, requireNotNull(cash), "crash harness expense",
                            ExpenseInstruction.AccrueAndSettle("OPERATING", BigDecimal("1.00"), PaymentMethod.CASH))
                        AccountingCrashOperation.Close -> AccountingCommandDraft.CashSessionClose(commandId, requireNotNull(cash), BigDecimal.ZERO, "crash harness close")
                        AccountingCrashOperation.PaymentCapture -> AccountingCommandDraft.PaymentCapture(
                            commandId, requireNotNull(identity), PaymentMethod.CASH, BigDecimal("10.00"))
                        AccountingCrashOperation.PaymentReverse -> AccountingCommandDraft.PaymentReverse(
                            commandId, requireNotNull(identity), requireNotNull(originalPayment), "crash harness refund", "crash-harness-evidence")
                        AccountingCrashOperation.Recognition -> fail("recognition has a dedicated worker crash test")
                        AccountingCrashOperation.Unknown -> fail("unknown crash operation")
                    }
                    val applicationName = "clr-crash-${UUID.randomUUID()}"
                    val before = factCounts(source)
                    val child = ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp",
                        System.getProperty("blackstore.test.classpath"), AccountingMutationCrashChild::class.java.name,
                        boundary.name, operation.name, staff.id.value.toString(), commandId.toString(),
                        (cash ?: 0).toString(), applicationName)
                        .redirectErrorStream(true)
                        .apply {
                            environment().remove("BLACKSTORE_TEST_JDBC_URL")
                            environment().remove("BLACKSTORE_TEST_JDBC_USER")
                            environment().remove("BLACKSTORE_TEST_JDBC_PASSWORD")
                            environment()["BLACKSTORE_CRASH_JDBC_URL"] = jdbcUrl
                            environment()["BLACKSTORE_CRASH_JDBC_USER"] = role
                            environment()["BLACKSTORE_CRASH_JDBC_PASSWORD"] = password
                            identity?.let {
                                environment()["BLACKSTORE_CRASH_CLIENT_ID"] = it.clientInstanceId
                                environment()["BLACKSTORE_CRASH_DEVICE_ID"] = it.deviceId
                                environment()["BLACKSTORE_CRASH_SALE_ID"] = it.saleId
                                environment()["BLACKSTORE_CRASH_OPERATION_ID"] = it.operationId
                            }
                            originalPayment?.let { environment()["BLACKSTORE_CRASH_PAYMENT_ID"] = it.toString() }
                        }.start()
                    try {
                        val line = reader.submit<String> { child.inputStream.bufferedReader().readLine() }.get(20, TimeUnit.SECONDS)
                        assertEquals("CLR_CRASH_READY", line,
                            "operation=$operation boundary=$boundary child failed before the controlled v2 commit boundary")
                        child.destroyForcibly()
                        assertTrue(child.waitFor(10, TimeUnit.SECONDS))
                        awaitNoConnection(source, applicationName)

                        val recovered = JdbcAccountingMutationCommands(source).findReceipt(staff, commandId)
                        if (boundary == AccountingCrashBoundary.BeforeCommit) {
                            assertEquals(AccountingCommandResult.NotFound, recovered)
                            assertEquals(before, factCounts(source), "precommit crash left partial facts for $operation")
                        } else {
                            assertTrue(recovered is AccountingCommandResult.Committed,
                                "operation=$operation boundary=$boundary recovered=$recovered")
                        }
                        val replay = applied(JdbcAccountingMutationCommands(source).execute(staff, command))
                        if (recovered is AccountingCommandResult.Committed) assertEquals(recovered.receipt, replay)
                        assertEquals("1", scalar(source, "SELECT count(*) FROM accounting_command_receipts WHERE command_id='$commandId'"))
                        assertEquals("1", scalar(source, "SELECT count(*) FROM audit_events WHERE payload_redacted::text LIKE '%$commandId%'"))
                        when (operation) {
                            AccountingCrashOperation.Open -> {
                                assertEquals("1", scalar(source,
                                    "SELECT count(*) FROM cash_session_projection WHERE id=${replay.cashSessionId} AND status='OPEN'"))
                                assertEquals("1", scalar(source,
                                    "SELECT count(*) FROM cash_accounting_coverage WHERE cash_session_id=${replay.cashSessionId} AND coverage='COMPLETE_FROM_OPENING'"))
                                assertEquals("OPENING:0.00", scalar(source,
                                    "SELECT event_type||':'||amount_delta::text FROM cash_ledger_events WHERE command_id='$commandId'"))
                            }
                            AccountingCrashOperation.Expense -> {
                                assertEquals("1", scalar(source, "SELECT count(*) FROM expenses WHERE id=${replay.expenseId}"))
                                assertEquals("1", scalar(source, "SELECT count(*) FROM expense_settlements WHERE id=${replay.settlementId} AND expense_id=${replay.expenseId} AND amount=1.00"))
                                assertEquals("EXPENSE_ACCRUAL:1.00,EXPENSE_PAID:-1.00", scalar(source,
                                    "SELECT string_agg(event_type||':'||amount_delta::text,',' ORDER BY local_sequence) FROM cash_ledger_events WHERE command_id='$commandId'"))
                            }
                            AccountingCrashOperation.Close -> {
                                assertEquals("1", scalar(source, "SELECT count(*) FROM cash_reconciliations WHERE command_id='$commandId'"))
                                assertEquals("CLOSED", scalar(source, "SELECT status FROM cash_session_projection WHERE id=${replay.cashSessionId}"))
                                assertEquals("0", scalar(source, "SELECT count(*) FROM cash_ledger_events WHERE command_id='$commandId'"))
                                assertEquals(1L, requireNotNull(replay.closeSnapshot).localWatermark)
                            }
                            AccountingCrashOperation.PaymentCapture -> {
                                assertEquals("1", scalar(source,
                                    "SELECT count(*) FROM payments WHERE id=${replay.paymentId} AND status='CAPTURED' AND amount=10.00"))
                                assertEquals("1", scalar(source, "SELECT count(*) FROM payments WHERE accounting_command_id='$commandId'"))
                                assertEquals("PAYMENT:10.00", scalar(source,
                                    "SELECT event_type||':'||amount_delta::text FROM cash_ledger_events WHERE command_id='$commandId'"))
                            }
                            AccountingCrashOperation.PaymentReverse -> {
                                assertEquals("1", scalar(source,
                                    "SELECT count(*) FROM payments WHERE id=${replay.paymentId} AND status='REFUNDED' AND original_payment_id=$originalPayment AND amount=10.00"))
                                assertEquals("1", scalar(source, "SELECT count(*) FROM payments WHERE accounting_command_id='$commandId'"))
                                assertEquals("REFUND:-10.00", scalar(source,
                                    "SELECT event_type||':'||amount_delta::text FROM cash_ledger_events WHERE command_id='$commandId'"))
                            }
                            AccountingCrashOperation.Recognition -> fail("recognition has a dedicated worker crash test")
                            AccountingCrashOperation.Unknown -> fail("unknown crash operation")
                        }
                        if (operation != AccountingCrashOperation.Close) {
                            sql(source, "UPDATE cash_session_projection SET status='CLOSED',closed_at=clock_timestamp(),closing_cash_declared=0 WHERE id=${replay.cashSessionId}")
                        }
                    } finally {
                        if (child.isAlive) {
                            child.destroyForcibly()
                            child.waitFor(10, TimeUnit.SECONDS)
                        }
                    }
                }
            }
        } finally {
            reader.shutdownNow()
            sql(source, "REVOKE blackstore_app, blackstore_projection_worker FROM $role")
            sql(source, "DROP ROLE $role")
        }
    }

    @Test fun commitConfirmedButResponseLostIsRecoveredByExactReceipt() = database { source ->
        activate(source)
        val reliable = JdbcAccountingMutationCommands(source)
        val cash = applied(reliable.execute(staff, opening())).cashSessionId
        val command = AccountingCommandDraft.ExpenseRecord(UUID.randomUUID(), cash, "uncertain commit expense",
            ExpenseInstruction.AccrueAndSettle("OPERATING", BigDecimal("2.00"), PaymentMethod.CASH))
        assertEquals(AccountingCommandFailure.Unavailable,
            rejected(JdbcAccountingMutationCommands(ThrowAfterCommitSource(source)).execute(staff, command)))
        val recovered = reliable.findReceipt(staff, command.commandId) as AccountingCommandResult.Committed
        assertEquals(recovered.receipt, applied(reliable.execute(staff, command)))
        assertEquals("1", scalar(source, "SELECT count(*) FROM accounting_command_receipts WHERE command_id='${command.commandId}'"))
        assertEquals(recovered.receipt.ledgerEventIds.size.toString(), scalar(source,
            "SELECT count(*) FROM cash_ledger_events WHERE command_id='${command.commandId}'"))
    }

    @Test fun workerRecognitionChildCrashIsAtomicBeforeAndAfterCommit() = database { source ->
        activate(source)
        val commands = JdbcAccountingMutationCommands(source)
        val jdbcUrl = source.connection.use { it.metaData.url }
        require(URI(jdbcUrl.removePrefix("jdbc:")).host in setOf("localhost", "127.0.0.1", "::1"))
        val role = "clr_worker_${UUID.randomUUID().toString().replace("-", "")}"
        val password = UUID.randomUUID().toString()
        sql(source, "CREATE ROLE $role LOGIN NOINHERIT PASSWORD '$password'")
        sql(source, "GRANT blackstore_app, blackstore_projection_worker, blackstore_outbox_worker TO $role")
        val reader = Executors.newSingleThreadExecutor()
        try {
            for (boundary in AccountingCrashBoundary.entries.filter { it != AccountingCrashBoundary.Unknown }) {
                val fixture = committable(source, commands)
                val before = workerFactCounts(source, fixture.committed.quadruple.operationId)
                val applicationName = "clr-worker-crash-${UUID.randomUUID()}"
                val child = ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp",
                    System.getProperty("blackstore.test.classpath"), AccountingMutationCrashChild::class.java.name,
                    boundary.name, AccountingCrashOperation.Recognition.name, staff.id.value.toString(), UUID.randomUUID().toString(),
                    fixture.cashId.toString(), applicationName)
                    .redirectErrorStream(true)
                    .apply {
                        environment().remove("BLACKSTORE_TEST_JDBC_URL")
                        environment().remove("BLACKSTORE_TEST_JDBC_USER")
                        environment().remove("BLACKSTORE_TEST_JDBC_PASSWORD")
                        environment()["BLACKSTORE_CRASH_JDBC_URL"] = jdbcUrl
                        environment()["BLACKSTORE_CRASH_JDBC_USER"] = role
                        environment()["BLACKSTORE_CRASH_JDBC_PASSWORD"] = password
                        environment()["BLACKSTORE_CRASH_CLIENT_ID"] = fixture.committed.quadruple.clientInstanceId
                        environment()["BLACKSTORE_CRASH_DEVICE_ID"] = fixture.committed.quadruple.deviceId
                        environment()["BLACKSTORE_CRASH_SALE_ID"] = fixture.committed.quadruple.saleId
                        environment()["BLACKSTORE_CRASH_OPERATION_ID"] = fixture.committed.quadruple.operationId
                        environment()["BLACKSTORE_CRASH_CLAIM_ID"] = fixture.claim.id.toString()
                        environment()["BLACKSTORE_CRASH_CLAIM_TOKEN"] = fixture.claim.claimToken.toString()
                        environment()["BLACKSTORE_CRASH_CLAIM_EPOCH"] = fixture.claim.claimEpoch.toString()
                        environment()["BLACKSTORE_CRASH_CLAIM_ATTEMPTS"] = fixture.claim.attempts.toString()
                        environment()["BLACKSTORE_CRASH_CLAIM_ACTOR"] = fixture.claim.actorId.toString()
                    }.start()
                try {
                    val line = reader.submit<String> { child.inputStream.bufferedReader().readLine() }.get(20, TimeUnit.SECONDS)
                    assertEquals("CLR_CRASH_READY", line, "worker boundary=$boundary failed before controlled commit")
                    child.destroyForcibly()
                    assertTrue(child.waitFor(10, TimeUnit.SECONDS))
                    awaitNoConnection(source, applicationName)
                    if (boundary == AccountingCrashBoundary.BeforeCommit) {
                        assertEquals(before, workerFactCounts(source, fixture.committed.quadruple.operationId))
                        assertEquals(AttemptOutcome.APPLIED, JdbcDurableSaleRepository(source).applyClaimEvidence(
                            fixture.claim, fixture.committed, "e".repeat(64), "COMMITTED", fixture.receipt))
                    } else {
                        val committed = workerFactCounts(source, fixture.committed.quadruple.operationId)
                        assertEquals("COMMITTED", committed.saleStatus)
                        assertEquals("APPLIED", committed.deliveryState)
                        assertEquals(before.recognitions + 1, committed.recognitions)
                        assertEquals(before.recognitionReceipts + 1, committed.recognitionReceipts)
                        assertEquals(AttemptOutcome.LATE_IGNORED, JdbcDurableSaleRepository(source).applyClaimEvidence(
                            fixture.claim, fixture.committed, "e".repeat(64), "COMMITTED", fixture.receipt))
                    }
                    val afterReplay = workerFactCounts(source, fixture.committed.quadruple.operationId)
                    assertEquals("COMMITTED", afterReplay.saleStatus)
                    assertEquals("APPLIED", afterReplay.deliveryState)
                    assertEquals(before.recognitions + 1, afterReplay.recognitions)
                    assertEquals(before.recognitionReceipts + 1, afterReplay.recognitionReceipts)
                    assertEquals(before.inboxApplications + 1, afterReplay.inboxApplications)
                    assertEquals(before.audits + 2, afterReplay.audits)
                    applied(commands.execute(staff, AccountingCommandDraft.CashSessionClose(
                        UUID.randomUUID(), fixture.cashId, BigDecimal.TEN, "worker crash scenario cleanup")))
                } finally {
                    if (child.isAlive) { child.destroyForcibly(); child.waitFor(10, TimeUnit.SECONDS) }
                }
            }
        } finally {
            reader.shutdownNow()
            sql(source, "REVOKE blackstore_app, blackstore_projection_worker, blackstore_outbox_worker FROM $role")
            sql(source, "DROP ROLE $role")
        }
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

    @Test fun activeRejectsLegacyReleaseBeforeOutboxOrProjectionMutation() = database { source ->
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
        val denied = assertThrows(AccountingAdmissionException::class.java) { JdbcSaleRecordStore(source).recordReleasePending(pending) }
        assertEquals(AccountingCommandFailure.LegacyContractDisabled, denied.failure)
        assertEquals("PAYMENT_CAPTURED", scalar(source, "SELECT status FROM sale_state_projection"))
        assertEquals("0", scalar(source, "SELECT count(*) FROM storecore_outbox_commands WHERE operation_kind='RELEASE'"))
    }

    @Test fun activeRejectsLegacyReservationBeforeIntentOrOutbox() = database { source ->
        activate(source)
        val cash = applied(JdbcAccountingMutationCommands(source).execute(staff, opening())).cashSessionId
        val identity = OperationQuadruple(UUID.randomUUID().toString(), "POS", "legacy-sale", UUID.randomUUID().toString())
        val command = OutboxCommand(identity, StoreCoreOperationKind.RESERVE,
            com.blackstore.domain.model.StoreCoreCanonicalContract.CANONICAL_PATH,
            com.blackstore.domain.model.StoreCoreCanonicalContract.VERSION,
            com.blackstore.domain.model.StoreCoreCanonicalContract.SHA256, "d".repeat(64),
            payload = CanonicalCommandPayload.Reserve("catalog-v1", listOf(CanonicalReserveLine("SKU", 1, "price-v1"))))
        val saga = SaleSaga(identity, cash, outbox = listOf(command), lines = listOf(TicketLine("SKU", "Product", 1, BigDecimal.TEN, BigDecimal.ZERO)),
            createdBy = staff.id.value, staffCommandAudit = SaleStaffCommandAudit(SaleStaffCommandEvent.RESERVE_REQUESTED, staff.id, null))
        val denied = assertThrows(AccountingAdmissionException::class.java) { JdbcSaleRecordStore(source).recordIntentAndOutbox(saga) }
        assertEquals(AccountingCommandFailure.LegacyContractDisabled, denied.failure)
        assertEquals("0", scalar(source, "SELECT count(*) FROM sale_intents"))
        assertEquals("0", scalar(source, "SELECT count(*) FROM storecore_outbox_commands"))
    }

    @Test fun activeRejectsLegacyServiceReleaseWithoutCallingStoreCore() = database { source ->
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

        val denied = assertThrows(AccountingAdmissionException::class.java) { service.release(staff, identity.operationId, null) }
        assertEquals(AccountingCommandFailure.LegacyContractDisabled, denied.failure)
        assertEquals("PAYMENT_CAPTURED", scalar(source, "SELECT status FROM sale_state_projection"))
        assertEquals("0", scalar(source, "SELECT count(*) FROM storecore_outbox_commands WHERE operation_kind='RELEASE'"))
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

    @Test fun expenseProjectionCorrelatesOriginalSourcesAndRemainsFixedAfterSettlement() = database { source ->
        activate(source)
        val commands = JdbcAccountingMutationCommands(source)
        val openingReceipt = applied(commands.execute(staff, opening()))
        val cash = openingReceipt.cashSessionId
        val at = java.time.Instant.now()
        val digest = "e".repeat(64)
        sql(source, "INSERT INTO staff_sessions(token_digest,user_id,csrf_token,created_at,last_used_at,expires_at) VALUES('$digest',1,'csrf',clock_timestamp(),clock_timestamp(),clock_timestamp()+interval '1 hour')")
        val session = ResolvedStaffSession(staff, StaffSession(digest, staff.id, "csrf", at, at, at.plusSeconds(3600), null))
        val query = JdbcExpenseCommandProjectionQuery(source)
        val accrued = applied(commands.execute(staff, AccountingCommandDraft.ExpenseRecord(UUID.randomUUID(), cash, "rent invoice",
            ExpenseInstruction.Accrue("OPERATING", BigDecimal("5.00")))))
        fun found(id: UUID) = (query.read(session, id) as ExpenseCommandProjectionResult.Found).projection
        val original = found(accrued.commandId)
        assertEquals(ExpenseProjectionOperation.Accrue, original.operation)
        assertNull(original.expense.paymentMethod)
        val settled = applied(commands.execute(staff, AccountingCommandDraft.ExpenseRecord(UUID.randomUUID(), cash, "rent payment",
            ExpenseInstruction.SettleExisting(accrued.expenseId!!, PaymentMethod.TRANSFER))))
        assertEquals(ExpenseProjectionOperation.SettleExisting, found(settled.commandId).operation)
        assertEquals(settled.ledgerEventIds, found(settled.commandId).ledgerEventIds)
        assertEquals(original.operation, found(accrued.commandId).operation)
        assertNull(found(accrued.commandId).settlement)
        val immediate = applied(commands.execute(staff, AccountingCommandDraft.ExpenseRecord(UUID.randomUUID(), cash, "paid supplies",
            ExpenseInstruction.AccrueAndSettle("OPERATING", BigDecimal("2.00"), PaymentMethod.CASH))))
        assertEquals(ExpenseProjectionOperation.AccrueAndSettle, found(immediate.commandId).operation)
        assertEquals(ExpenseCommandProjectionResult.NotFound, query.read(session, openingReceipt.commandId))
        assertEquals(ExpenseCommandProjectionResult.NotFound, query.read(session, UUID.randomUUID()))
        val other = AuthenticatedStaff(StaffUserId(3), "Other", StaffRole.CASHIER)
        val otherDigest = "c".repeat(64)
        sql(source, "INSERT INTO staff_sessions(token_digest,user_id,csrf_token,created_at,last_used_at,expires_at) VALUES('$otherDigest',3,'csrf',clock_timestamp(),clock_timestamp(),clock_timestamp()+interval '1 hour')")
        val otherSession = ResolvedStaffSession(other, session.session.copy(digest = otherDigest, userId = other.id))
        assertEquals(ExpenseCommandProjectionResult.NotFound, query.read(otherSession, accrued.commandId))
        assertEquals(ExpenseCommandProjectionResult.NotFound, query.read(otherSession, UUID.randomUUID()))
        val before = scalar(source, "SELECT count(*) FROM accounting_command_receipts")
        applied(commands.execute(staff, AccountingCommandDraft.CashSessionClose(UUID.randomUUID(), cash, BigDecimal.ZERO, "close shift")))
        sql(source, "UPDATE accounting_runtime SET state='PAUSED'")
        assertEquals(ExpenseProjectionOperation.Accrue, found(accrued.commandId).operation)
        assertEquals((before.toInt() + 1).toString(), scalar(source, "SELECT count(*) FROM accounting_command_receipts"))
        assertEquals("2", scalar(source, "SELECT count(*) FROM expenses WHERE paid_at IS NULL"))
        sql(source, "INSERT INTO roles(code) VALUES('AUDITOR')")
        sql(source, "UPDATE staff_users SET role_code='AUDITOR' WHERE id=1")
        assertEquals(StaffSecurityFailure.FORBIDDEN, assertThrows(StaffSecurityException::class.java) {
            query.read(session, UUID.randomUUID())
        }.failure)
        sql(source, "UPDATE staff_users SET role_code='CASHIER' WHERE id=1")
        sql(source, "ALTER TABLE accounting_command_receipts DISABLE TRIGGER immutable_accounting_history")
        sql(source, "UPDATE accounting_command_receipts SET result=result-'expenseId' WHERE command_id='${accrued.commandId}'")
        sql(source, "ALTER TABLE accounting_command_receipts ENABLE TRIGGER immutable_accounting_history")
        assertEquals(ExpenseCommandProjectionResult.Unavailable, query.read(session, accrued.commandId))
        sql(source, "UPDATE staff_sessions SET revoked_at=clock_timestamp() WHERE token_digest='$digest'")
        assertEquals(StaffSecurityFailure.SESSION_INVALID, assertThrows(StaffSecurityException::class.java) {
            query.read(session, accrued.commandId)
        }.failure)
    }

    @Test fun expenseProjectionSnapshotExcludesSettlementCommittedAfterItsAuthoritySnapshot() = database { source ->
        activate(source)
        val commands = JdbcAccountingMutationCommands(source)
        val cash = applied(commands.execute(staff, opening())).cashSessionId
        val accrued = applied(commands.execute(staff, AccountingCommandDraft.ExpenseRecord(UUID.randomUUID(), cash, "rent invoice",
            ExpenseInstruction.Accrue("OPERATING", BigDecimal("5.00")))))
        val digest = "f".repeat(64)
        val at = java.time.Instant.now()
        sql(source, "INSERT INTO staff_sessions(token_digest,user_id,csrf_token,created_at,last_used_at,expires_at) VALUES('$digest',1,'csrf',clock_timestamp(),clock_timestamp(),clock_timestamp()+interval '1 hour')")
        val session = ResolvedStaffSession(staff, StaffSession(digest, staff.id, "csrf", at, at, at.plusSeconds(3600), null))
        val settle = AccountingCommandDraft.ExpenseRecord(UUID.randomUUID(), cash, "rent payment", ExpenseInstruction.SettleExisting(accrued.expenseId!!, PaymentMethod.CARD))
        val reached = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val paused = object : DataSource by source {
            override fun getConnection(): Connection {
                val connection = source.connection
                return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                    if (method.name == "prepareStatement" && (args?.firstOrNull() as? String)?.startsWith("SELECT * FROM accounting_command_receipts") == true) {
                        reached.countDown()
                        check(proceed.await(15, TimeUnit.SECONDS))
                    }
                    try { method.invoke(connection, *(args ?: emptyArray())) }
                    catch (error: InvocationTargetException) { throw error.targetException }
                } as Connection
            }
        }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val before = pool.submit<ExpenseCommandProjectionResult> { JdbcExpenseCommandProjectionQuery(paused).read(session, settle.commandId) }
            assertTrue(reached.await(15, TimeUnit.SECONDS))
            val committed = applied(commands.execute(staff, settle))
            proceed.countDown()
            assertEquals(ExpenseCommandProjectionResult.NotFound, before.get(15, TimeUnit.SECONDS))
            val after = JdbcExpenseCommandProjectionQuery(source).read(session, settle.commandId) as ExpenseCommandProjectionResult.Found
            assertEquals(committed.ledgerEventIds, after.projection.ledgerEventIds)
            assertEquals(ExpenseProjectionOperation.SettleExisting, after.projection.operation)
        } finally { proceed.countDown(); pool.shutdownNow() }
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

    @Test fun expenseThenCloseAndCloseThenExpenseSerializeWithoutDeadlock() = database { source ->
        activate(source)
        val commands = JdbcAccountingMutationCommands(source)
        val cash = applied(commands.execute(staff, opening().copy(openingCash = BigDecimal.TEN))).cashSessionId
        val pool = Executors.newFixedThreadPool(2)
        try {
            val expenseFirst = PausingDataSource(source, "SELECT state FROM accounting_runtime WHERE singleton")
            val expense = pool.submit<AccountingMutationOutcome> {
                JdbcAccountingMutationCommands(expenseFirst).execute(staff, AccountingCommandDraft.ExpenseRecord(
                    UUID.randomUUID(), cash, "paid supplies", ExpenseInstruction.AccrueAndSettle("OPERATING", BigDecimal("2"), PaymentMethod.CASH)))
            }
            assertTrue(expenseFirst.reached.await(5, TimeUnit.SECONDS))
            val close = pool.submit<AccountingMutationOutcome> {
                commands.execute(staff, AccountingCommandDraft.CashSessionClose(UUID.randomUUID(), cash, BigDecimal("8"), "close after expense"))
            }
            expenseFirst.proceed.countDown()
            assertTrue(expense.get(15, TimeUnit.SECONDS) is AccountingMutationOutcome.Applied)
            val closed = applied(close.get(15, TimeUnit.SECONDS))
            assertEquals(BigDecimal("8.00"), closed.closeSnapshot?.expectedCash)
            assertEquals(ReconciliationOutcome.Balanced, closed.closeSnapshot?.outcome)
            assertEquals("1", scalar(source, "SELECT count(*) FROM expense_settlements"))
        } finally { pool.shutdownNow() }
    }

    @Test fun closeWinnerRejectsConcurrentExpenseAndRevalidatesAuthorityAfterCashLock() = database { source ->
        activate(source)
        val commands = JdbcAccountingMutationCommands(source)
        val cash = applied(commands.execute(staff, opening())).cashSessionId
        val pool = Executors.newFixedThreadPool(2)
        try {
            val closeFirst = PausingDataSource(source, "FROM sale_intents i LEFT JOIN sale_state_projection")
            val close = pool.submit<AccountingMutationOutcome> {
                JdbcAccountingMutationCommands(closeFirst).execute(staff,
                    AccountingCommandDraft.CashSessionClose(UUID.randomUUID(), cash, BigDecimal.ZERO, "close first"))
            }
            assertTrue(closeFirst.reached.await(5, TimeUnit.SECONDS))
            val expense = pool.submit<AccountingMutationOutcome> {
                commands.execute(staff, AccountingCommandDraft.ExpenseRecord(UUID.randomUUID(), cash, "late expense",
                    ExpenseInstruction.AccrueAndSettle("OPERATING", BigDecimal.ONE, PaymentMethod.CASH)))
            }
            closeFirst.proceed.countDown()
            assertTrue(close.get(15, TimeUnit.SECONDS) is AccountingMutationOutcome.Applied)
            assertEquals(AccountingCommandFailure.Closed, rejected(expense.get(15, TimeUnit.SECONDS)))
            assertEquals("0", scalar(source, "SELECT count(*) FROM expenses"))

            // A new session proves actor eligibility is read only after the row lock is obtained.
            sql(source, "UPDATE staff_users SET active=true WHERE id=1")
            sql(source, "INSERT INTO terminals(terminal_code) VALUES('POS-2')")
            val open = opening().copy(commandId = UUID.randomUUID(), terminalId = 2)
            val secondCash = applied(commands.execute(staff, open)).cashSessionId
            source.connection.use { blocker ->
                blocker.autoCommit = false
                blocker.createStatement().execute("SET LOCAL ROLE blackstore_projection_worker")
                blocker.prepareStatement("SELECT * FROM cash_session_projection WHERE id=? FOR UPDATE").use { lock ->
                    lock.setLong(1, secondCash); lock.executeQuery().use { assertTrue(it.next()) }
                }
                val waiting = PausingDataSource(source, "SELECT * FROM cash_session_projection WHERE id=? FOR UPDATE")
                val denied = pool.submit<AccountingMutationOutcome> {
                    JdbcAccountingMutationCommands(waiting).execute(staff,
                        AccountingCommandDraft.CashSessionClose(UUID.randomUUID(), secondCash, BigDecimal.ZERO, "authority changed"))
                }
                assertTrue(waiting.reached.await(5, TimeUnit.SECONDS))
                sql(source, "UPDATE staff_users SET active=false WHERE id=1")
                waiting.proceed.countDown()
                blocker.commit()
                assertEquals(AccountingCommandFailure.Forbidden, rejected(denied.get(15, TimeUnit.SECONDS)))
            }
            assertEquals("OPEN", scalar(source, "SELECT status FROM cash_session_projection WHERE id=$secondCash"))
            assertEquals("0", scalar(source, "SELECT count(*) FROM cash_reconciliations WHERE cash_session_id=$secondCash"))
        } finally { pool.shutdownNow() }
    }

    @Test fun paymentAndReverseWinnersRemainVisibleToConcurrentClose() = database { source ->
        activate(source)
        val commands = JdbcAccountingMutationCommands(source)
        val cash = applied(commands.execute(staff, opening())).cashSessionId
        val identity = sale(source, cash)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val paymentFirst = PausingDataSource(source, "SELECT state FROM accounting_runtime WHERE singleton")
            val captureDraft = AccountingCommandDraft.PaymentCapture(UUID.randomUUID(), identity, PaymentMethod.CASH, BigDecimal.TEN)
            val capture = pool.submit<AccountingMutationOutcome> { JdbcAccountingMutationCommands(paymentFirst).execute(staff, captureDraft) }
            assertTrue(paymentFirst.reached.await(5, TimeUnit.SECONDS))
            val closeAfterPayment = pool.submit<AccountingMutationOutcome> { commands.execute(staff,
                AccountingCommandDraft.CashSessionClose(UUID.randomUUID(), cash, BigDecimal.TEN, "close racing payment")) }
            paymentFirst.proceed.countDown()
            val payment = applied(capture.get(15, TimeUnit.SECONDS))
            assertEquals(AccountingCommandFailure.NonTerminalSale, rejected(closeAfterPayment.get(15, TimeUnit.SECONDS)))

            val reverseFirst = PausingDataSource(source, "SELECT state FROM accounting_runtime WHERE singleton")
            val reverse = pool.submit<AccountingMutationOutcome> { JdbcAccountingMutationCommands(reverseFirst).execute(staff,
                AccountingCommandDraft.PaymentReverse(UUID.randomUUID(), identity, requireNotNull(payment.paymentId), "refund", "refund-proof")) }
            assertTrue(reverseFirst.reached.await(5, TimeUnit.SECONDS))
            val closeAfterReverse = pool.submit<AccountingMutationOutcome> { commands.execute(staff,
                AccountingCommandDraft.CashSessionClose(UUID.randomUUID(), cash, BigDecimal.ZERO, "close racing reverse")) }
            reverseFirst.proceed.countDown()
            assertTrue(reverse.get(15, TimeUnit.SECONDS) is AccountingMutationOutcome.Applied)
            assertEquals(AccountingCommandFailure.NonTerminalSale, rejected(closeAfterReverse.get(15, TimeUnit.SECONDS)))
            assertEquals("0.00", scalar(source, "SELECT sum(amount_delta)::numeric(14,2)::text FROM cash_ledger_events WHERE event_type IN ('PAYMENT','REFUND')"))
            assertEquals("OPEN", scalar(source, "SELECT status FROM cash_session_projection WHERE id=$cash"))
        } finally { pool.shutdownNow() }
    }

    @Test fun workerRecognitionAndCloseSerializeInBothOrders() = database { source ->
        activate(source)
        val commands = JdbcAccountingMutationCommands(source)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = committable(source, commands)
            val predictableOldId = UUID.nameUUIDFromBytes("COMMERCIAL_RECOGNITION:${first.committed.quadruple.operationId}".toByteArray())
            assertTrue(commands.execute(staff, AccountingCommandDraft.ExpenseRecord(predictableOldId, first.cashId,
                "occupy formerly predictable id", ExpenseInstruction.Accrue("OPERATING", BigDecimal.ONE))) is AccountingMutationOutcome.Applied)
            val workerFirst = PausingDataSource(source, "UPDATE storecore_command_delivery SET state=?")
            val appliedWorker = pool.submit<AttemptOutcome> { JdbcDurableSaleRepository(workerFirst).applyClaimEvidence(
                first.claim, first.committed, "e".repeat(64), "COMMITTED", first.receipt) }
            assertTrue(workerFirst.reached.await(5, TimeUnit.SECONDS))
            val closeAfterWorker = pool.submit<AccountingMutationOutcome> { commands.execute(staff,
                AccountingCommandDraft.CashSessionClose(UUID.randomUUID(), first.cashId, BigDecimal.TEN, "worker first")) }
            workerFirst.proceed.countDown()
            assertEquals(AttemptOutcome.APPLIED, appliedWorker.get(15, TimeUnit.SECONDS))
            assertEquals(ReconciliationOutcome.Balanced, applied(closeAfterWorker.get(15, TimeUnit.SECONDS)).closeSnapshot?.outcome)
            assertEquals("1", scalar(source, "SELECT count(*) FROM commercial_recognitions WHERE cash_session_id=${first.cashId}"))
            assertEquals("10.00", scalar(source, "SELECT net_sales::text FROM commercial_recognitions WHERE cash_session_id=${first.cashId}"))
            assertEquals("EXPENSE_RECORD", scalar(source, "SELECT command_kind FROM accounting_command_receipts WHERE command_id='$predictableOldId'"))
            assertEquals("false", scalar(source, "SELECT (command_id='$predictableOldId')::text FROM commercial_recognitions WHERE cash_session_id=${first.cashId}"))

            val second = committable(source, commands)
            val closeFirst = PausingDataSource(source, "FROM sale_intents i LEFT JOIN sale_state_projection")
            val earlyClose = pool.submit<AccountingMutationOutcome> { JdbcAccountingMutationCommands(closeFirst).execute(staff,
                AccountingCommandDraft.CashSessionClose(UUID.randomUUID(), second.cashId, BigDecimal.TEN, "close first")) }
            assertTrue(closeFirst.reached.await(5, TimeUnit.SECONDS))
            val workerAfterClose = pool.submit<AttemptOutcome> { JdbcDurableSaleRepository(source).applyClaimEvidence(
                second.claim, second.committed, "f".repeat(64), "COMMITTED", second.receipt) }
            closeFirst.proceed.countDown()
            assertEquals(AccountingCommandFailure.NonTerminalSale, rejected(earlyClose.get(15, TimeUnit.SECONDS)))
            assertEquals(AttemptOutcome.APPLIED, workerAfterClose.get(15, TimeUnit.SECONDS))
            assertEquals(ReconciliationOutcome.Balanced, applied(commands.execute(staff,
                AccountingCommandDraft.CashSessionClose(UUID.randomUUID(), second.cashId, BigDecimal.TEN, "retry after worker"))).closeSnapshot?.outcome)
            assertEquals("1", scalar(source, "SELECT count(*) FROM commercial_recognitions WHERE cash_session_id=${second.cashId}"))
        } finally { pool.shutdownNow() }
    }

    @Test fun preActivationWorkerCommitRemainsLegacyWithoutV2AccountingFacts() = database { source ->
        sql(source, "INSERT INTO cash_session_projection(terminal_id,cashier_id,opened_at,opening_cash) VALUES(1,1,clock_timestamp(),0)")
        val identity = sale(source, 1)
        val repository = JdbcDurableSaleRepository(source)
        val current = requireNotNull(repository.findDurable(identity.operationId)).saga
        val evidence = requireNotNull(current.evidence)
        val command = OutboxCommand(identity, StoreCoreOperationKind.COMMIT,
            com.blackstore.domain.model.StoreCoreCanonicalContract.CANONICAL_PATH, evidence.contractVersion, evidence.openapiDigest,
            "9".repeat(64), reservationRef = evidence.reservationRef, payload = CanonicalCommandPayload.Terminal(evidence.reservationRef))
        val pending = current.markPaymentCaptured().withCommand(command).markCommitPending()
        source.connection.use { c ->
            c.autoCommit = false
            c.createStatement().execute("SET LOCAL ROLE blackstore_app")
            JdbcAccountingLifecycleAdmission().requireLegacy(c)
            val locked = JdbcAccountingAggregateLocks().lockSale(c, identity)
            JdbcSaleRecordStore(source).insertCanonicalCommand(c, pending, command, staff.id.value, "legacy commit fixture")
            check(JdbcBlackStoreWriter().advanceSaleStatus(c, locked.projectionId, SaleStatus.RESERVED.name, SaleStatus.PAYMENT_CAPTURED.name) == 1)
            check(JdbcBlackStoreWriter().advanceSaleStatus(c, locked.projectionId, SaleStatus.PAYMENT_CAPTURED.name, SaleStatus.COMMIT_PENDING.name) == 1)
            c.prepareStatement("UPDATE sale_state_projection SET version=version+1,updated_at=now() WHERE id=?").use { it.setLong(1, locked.projectionId); it.executeUpdate() }
            c.commit()
        }
        val claim = requireNotNull(repository.claimCommand(identity.operationId, CommandKind.COMMIT, Duration.ofSeconds(30)))
        val receipt = com.blackstore.domain.model.StoreCoreOperationReceipt(identity, StoreCoreOperationKind.COMMIT,
            com.blackstore.domain.model.StoreCoreOperationState.COMMITTED, evidence.reservationRef, "legacy-commit-receipt",
            com.blackstore.domain.model.StoreCoreContractRef(command.canonicalPath, command.contractVersion, command.openapiDigest),
            evidence.acceptedPriceVersions, null)
        val committed = requireNotNull(repository.findDurable(identity.operationId)).saga.applyRemoteDurable(
            com.blackstore.domain.model.StoreCoreOperationState.COMMITTED,
            RemoteEvidence(evidence.reservationRef, requireNotNull(receipt.receipt), evidence.contractVersion, evidence.openapiDigest,
                evidence.acceptedPriceVersions, null))
        assertEquals(AttemptOutcome.APPLIED, repository.applyClaimEvidence(claim, committed, "7".repeat(64), "COMMITTED", receipt))
        assertEquals("COMMITTED", scalar(source, "SELECT status FROM sale_state_projection"))
        assertEquals("APPLIED", scalar(source, "SELECT state FROM storecore_command_delivery WHERE command_id=${claim.id}"))
        assertEquals("1", scalar(source, "SELECT count(*) FROM storecore_inbox_applications WHERE command_id=${claim.id} AND state='APPLIED'"))
        assertEquals("0", scalar(source, "SELECT count(*) FROM accounting_command_receipts"))
        assertEquals("0", scalar(source, "SELECT count(*) FROM commercial_recognitions"))
        assertEquals("0.00", scalar(source, "SELECT gross_sales::text FROM sale_state_projection"))
    }

    @Test fun closeWinnerCannotHideConcurrentLegacySaleIntent() = database { source ->
        activate(source)
        val commands = JdbcAccountingMutationCommands(source)
        val cash = applied(commands.execute(staff, opening())).cashSessionId
        val identity = OperationQuadruple(UUID.randomUUID().toString(), "POS", "racing-sale", UUID.randomUUID().toString())
        val reserve = OutboxCommand(identity, StoreCoreOperationKind.RESERVE,
            com.blackstore.domain.model.StoreCoreCanonicalContract.CANONICAL_PATH,
            com.blackstore.domain.model.StoreCoreCanonicalContract.VERSION,
            com.blackstore.domain.model.StoreCoreCanonicalContract.SHA256, "a".repeat(64),
            payload = CanonicalCommandPayload.Reserve("catalog-v1", listOf(CanonicalReserveLine("SKU", 1, "price-v1"))))
        val intent = SaleSaga(identity, cash, outbox = listOf(reserve),
            lines = listOf(TicketLine("SKU", "Product", 1, BigDecimal.TEN, BigDecimal.ZERO)), createdBy = staff.id.value,
            staffCommandAudit = SaleStaffCommandAudit(SaleStaffCommandEvent.RESERVE_REQUESTED, staff.id, null))
        val closeFirst = PausingDataSource(source, "FROM sale_intents i LEFT JOIN sale_state_projection")
        val pool = Executors.newFixedThreadPool(2)
        try {
            val close = pool.submit<AccountingMutationOutcome> { JdbcAccountingMutationCommands(closeFirst).execute(staff,
                AccountingCommandDraft.CashSessionClose(UUID.randomUUID(), cash, BigDecimal.ZERO, "close before legacy intent")) }
            assertTrue(closeFirst.reached.await(5, TimeUnit.SECONDS))
            val attemptedIntent = pool.submit<Throwable?> { runCatching { JdbcSaleRecordStore(source).recordIntentAndOutbox(intent) }.exceptionOrNull() }
            closeFirst.proceed.countDown()
            assertTrue(close.get(15, TimeUnit.SECONDS) is AccountingMutationOutcome.Applied)
            assertNotNull(attemptedIntent.get(15, TimeUnit.SECONDS))
            assertEquals("0", scalar(source, "SELECT count(*) FROM sale_intents WHERE cash_session_id=$cash"))
            assertEquals("0", scalar(source, "SELECT count(*) FROM storecore_outbox_commands WHERE cash_session_id=$cash"))
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
        val reservationRef = "reservation-${identity.operationId}"
        val writer = JdbcBlackStoreWriter()
        source.connection.use { c ->
            c.autoCommit = false
            val id = writer.insertPendingSale(c, UUID.fromString(identity.clientInstanceId), identity.deviceId, identity.saleId,
                UUID.fromString(identity.operationId), cash, 1, contractVersion, openapiDigest)
            writer.insertSaleLine(c, id, "SKU", "Product", 1, BigDecimal.TEN, BigDecimal.ZERO)
            c.createStatement().execute("UPDATE sale_state_projection SET status='RESERVED',storecore_reservation_ref='$reservationRef',reservation_receipt='receipt',contract_version='$contractVersion',openapi_digest='$openapiDigest',accepted_price_versions='[\"price-v1\"]',reservation_expires_at=clock_timestamp()+interval '1 hour' WHERE id=$id")
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

    private data class CommittableSale(val cashId: Long, val claim: ClaimedSaleCommand, val committed: SaleSaga,
        val receipt: com.blackstore.domain.model.StoreCoreOperationReceipt)

    private fun committable(source: DataSource, commands: JdbcAccountingMutationCommands): CommittableSale {
        val cash = applied(commands.execute(staff, opening().copy(commandId = UUID.randomUUID()))).cashSessionId
        val identity = sale(source, cash)
        applied(commands.execute(staff, AccountingCommandDraft.PaymentCapture(UUID.randomUUID(), identity, PaymentMethod.CASH, BigDecimal.TEN)))
        val repository = JdbcDurableSaleRepository(source)
        val current = requireNotNull(repository.findDurable(identity.operationId)).saga
        val evidence = requireNotNull(current.evidence)
        val command = OutboxCommand(identity, StoreCoreOperationKind.COMMIT,
            com.blackstore.domain.model.StoreCoreCanonicalContract.CANONICAL_PATH, evidence.contractVersion, evidence.openapiDigest,
            "c".repeat(64), reservationRef = evidence.reservationRef, payload = CanonicalCommandPayload.Terminal(evidence.reservationRef))
        val pending = current.withCommand(command).markCommitPending()
        source.connection.use { c ->
            c.autoCommit = false
            c.createStatement().execute("SET LOCAL ROLE blackstore_app")
            JdbcAccountingLifecycleAdmission().requireV2(c)
            val locked = JdbcAccountingAggregateLocks().lockSale(c, identity)
            JdbcSaleRecordStore(source).insertCanonicalCommand(c, pending, command, staff.id.value, "v2 commit fixture")
            check(JdbcBlackStoreWriter().advanceSaleStatus(c, locked.projectionId, current.status.name, SaleStatus.COMMIT_PENDING.name) == 1)
            c.prepareStatement("UPDATE sale_state_projection SET version=version+1,updated_at=now() WHERE id=?").use { it.setLong(1, locked.projectionId); it.executeUpdate() }
            c.commit()
        }
        val claim = requireNotNull(repository.claimCommand(identity.operationId, CommandKind.COMMIT, Duration.ofSeconds(30)))
        val receipt = com.blackstore.domain.model.StoreCoreOperationReceipt(identity, StoreCoreOperationKind.COMMIT,
            com.blackstore.domain.model.StoreCoreOperationState.COMMITTED, evidence.reservationRef, "commit-receipt-${identity.saleId}",
            com.blackstore.domain.model.StoreCoreContractRef(command.canonicalPath, command.contractVersion, command.openapiDigest),
            evidence.acceptedPriceVersions, null)
        val committed = requireNotNull(repository.findDurable(identity.operationId)).saga.applyRemoteDurable(
            com.blackstore.domain.model.StoreCoreOperationState.COMMITTED,
            RemoteEvidence(evidence.reservationRef, requireNotNull(receipt.receipt), evidence.contractVersion, evidence.openapiDigest,
                evidence.acceptedPriceVersions, null))
        return CommittableSale(cash, claim, committed, receipt)
    }

    private fun scalar(source: DataSource, sql: String): String = source.connection.use { c -> c.createStatement().use { s -> s.executeQuery(sql).use { r -> r.next(); r.getString(1) } } }
    private fun sql(source: DataSource, sql: String) { source.connection.use { c -> c.createStatement().use { it.execute(sql) } } }
    private data class FactCounts(val cash: String, val coverage: String, val expenses: String, val settlements: String,
        val ledger: String, val reconciliations: String, val receipts: String, val audits: String,
        val payments: String, val cashProjection: String, val saleProjection: String)
    private fun factCounts(source: DataSource) = FactCounts(
        scalar(source, "SELECT count(*) FROM cash_session_projection"),
        scalar(source, "SELECT count(*) FROM cash_accounting_coverage"),
        scalar(source, "SELECT count(*) FROM expenses"),
        scalar(source, "SELECT count(*) FROM expense_settlements"),
        scalar(source, "SELECT count(*) FROM cash_ledger_events"),
        scalar(source, "SELECT count(*) FROM cash_reconciliations"),
        scalar(source, "SELECT count(*) FROM accounting_command_receipts"),
        scalar(source, "SELECT count(*) FROM audit_events"),
        scalar(source, "SELECT coalesce(jsonb_agg(jsonb_build_array(id,status,original_payment_id,amount,accounting_command_id) ORDER BY id)::text,'[]') FROM payments"),
        scalar(source, "SELECT coalesce(jsonb_agg(jsonb_build_array(id,status,closed_at,closing_cash_declared) ORDER BY id)::text,'[]') FROM cash_session_projection"),
        scalar(source, "SELECT coalesce(jsonb_agg(jsonb_build_array(id,status,version,gross_sales,discounts) ORDER BY id)::text,'[]') FROM sale_state_projection"),
    )
    private data class WorkerFactCounts(val saleStatus: String, val deliveryState: String, val saleSnapshot: String,
        val deliverySnapshot: String, val recognitions: Int,
        val recognitionReceipts: Int, val inboxApplications: Int, val audits: Int)
    private fun workerFactCounts(source: DataSource, operationId: String) = WorkerFactCounts(
        scalar(source, "SELECT status FROM sale_state_projection WHERE operation_id='$operationId'"),
        scalar(source, "SELECT d.state FROM storecore_command_delivery d JOIN storecore_outbox_commands o ON o.id=d.command_id WHERE o.operation_id='$operationId' AND o.operation_kind='COMMIT'"),
        scalar(source, "SELECT jsonb_build_array(status,version,gross_sales,discounts,reservation_receipt,updated_at)::text FROM sale_state_projection WHERE operation_id='$operationId'"),
        scalar(source, "SELECT jsonb_build_array(d.state,d.claim_token,d.claim_epoch,d.lease_until,d.updated_at)::text FROM storecore_command_delivery d JOIN storecore_outbox_commands o ON o.id=d.command_id WHERE o.operation_id='$operationId' AND o.operation_kind='COMMIT'"),
        scalar(source, "SELECT count(*) FROM commercial_recognitions r JOIN sale_state_projection s ON s.id=r.sale_id WHERE s.operation_id='$operationId'").toInt(),
        scalar(source, "SELECT count(*) FROM accounting_command_receipts r JOIN sale_state_projection s ON s.id=r.sale_id WHERE s.operation_id='$operationId' AND r.command_kind='COMMERCIAL_RECOGNITION'").toInt(),
        scalar(source, "SELECT count(*) FROM storecore_inbox_applications a JOIN storecore_outbox_commands o ON o.id=a.command_id WHERE o.operation_id='$operationId'").toInt(),
        scalar(source, "SELECT count(*) FROM audit_events a JOIN sale_state_projection s ON s.id=a.aggregate_id WHERE a.aggregate_type='sale' AND s.operation_id='$operationId'").toInt(),
    )
    private fun awaitNoConnection(source: DataSource, applicationName: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (scalar(source, "SELECT count(*) FROM pg_stat_activity WHERE application_name='$applicationName'") == "0") return
            Thread.onSpinWait()
        }
        fail<Unit>("crash child connection was not released")
    }
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

    private class ThrowAfterCommitSource(private val delegate: DataSource) : DataSource by delegate {
        private val armed = AtomicBoolean(true)
        override fun getConnection(): Connection {
            val connection = delegate.connection
            return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, arguments ->
                val result = try { method.invoke(connection, *(arguments ?: emptyArray())) }
                catch (error: InvocationTargetException) { throw error.targetException }
                if (method.name == "commit" && armed.compareAndSet(true, false)) throw SQLException("test-only response loss after commit")
                result
            } as Connection
        }
    }

    /** Test-only SQL barrier; it pauses one statement while the transaction retains earlier locks. */
    private class PausingDataSource(private val delegate: DataSource, private val sqlFragment: String) : DataSource by delegate {
        val reached = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        private val armed = AtomicBoolean(true)
        override fun getConnection(): Connection {
            val connection = delegate.connection
            return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, arguments ->
                try {
                    val result = method.invoke(connection, *(arguments ?: emptyArray()))
                    if (method.name != "prepareStatement" || result !is java.sql.PreparedStatement ||
                        !(arguments?.firstOrNull() as? String).orEmpty().contains(sqlFragment)) return@newProxyInstance result
                    Proxy.newProxyInstance(java.sql.PreparedStatement::class.java.classLoader,
                        arrayOf(java.sql.PreparedStatement::class.java)) { _, statementMethod, statementArguments ->
                        if (statementMethod.name in setOf("execute", "executeQuery", "executeUpdate") && armed.compareAndSet(true, false)) {
                            reached.countDown()
                            check(proceed.await(10, TimeUnit.SECONDS)) { "test SQL barrier timed out" }
                        }
                        try { statementMethod.invoke(result, *(statementArguments ?: emptyArray())) }
                        catch (error: InvocationTargetException) { throw error.targetException }
                    }
                } catch (error: InvocationTargetException) { throw error.targetException }
            } as Connection
        }
    }
}
