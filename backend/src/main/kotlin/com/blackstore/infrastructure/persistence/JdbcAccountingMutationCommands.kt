package com.blackstore.infrastructure.persistence

import com.blackstore.application.accounting.AccountingCommandFingerprint
import com.blackstore.application.accounting.AccountingLifecycleAdmissionStep
import com.blackstore.application.accounting.AccountingReceiptAccessPolicy
import com.blackstore.application.accounting.AccountingTransitionFailureMapper
import com.blackstore.domain.accounting.*
import com.blackstore.domain.cash.*
import com.blackstore.domain.identity.*
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.port.out.accounting.*
import com.blackstore.domain.sales.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** One physical transaction owns authority, aggregate locks, facts, receipt and audit. */
@Component
@ConditionalOnProperty(name = ["blackstore.persistence.enabled"], havingValue = "true")
class JdbcAccountingMutationCommands(private val source: DataSource) : AccountingMutationCommands, OperationalAccountingCommands {
    private val json = jacksonObjectMapper()
    private val writer = JdbcBlackStoreWriter()
    private val durable = JdbcDurableSaleRepository(source)
    private val cashPolicy = CashMutationPolicy()
    private val receiptAccess = AccountingReceiptAccessPolicy()
    private val lifecycleAdmission = AccountingLifecycleAdmissionStep()
    private val serializationStep = CommandSerializationStep()
    private val aggregateStep = AggregateScopeStep()
    private val authorityStep = CurrentAuthorityStep()
    private val replayStep = ReceiptReplayStep()
    private val lifecycleStep = RuntimeAdmissionStep()
    private val closeTerminality = VerifyCashCloseTerminality()
    private val closeCoverage = ResolveCashCloseCoverage()
    private val closeLedger = LoadCashCloseLedger()
    private val lifecycleFence = JdbcAccountingLifecycleAdmission()

    override fun execute(staff: AuthenticatedStaff, command: AccountingCommandDraft): AccountingMutationOutcome = outcome(staff) {
        val scope = when (command) {
            is AccountingCommandDraft.CashSessionOpen -> Scope(command.commandId, AccountingCommandKind.CASH_SESSION_OPEN, null, null, command.reason)
            is AccountingCommandDraft.ExpenseRecord -> Scope(command.commandId, AccountingCommandKind.EXPENSE_RECORD, command.cashSessionId, null, command.reason)
            is AccountingCommandDraft.PaymentCapture -> Scope(command.commandId, AccountingCommandKind.PAYMENT_CAPTURE, null, command.identity, command.reason)
            is AccountingCommandDraft.PaymentReverse -> Scope(command.commandId, AccountingCommandKind.PAYMENT_REVERSE, null, command.identity, command.reason)
            is AccountingCommandDraft.CashSessionClose -> Scope(command.commandId, AccountingCommandKind.CASH_SESSION_CLOSE, command.cashSessionId, null, command.reason)
            is AccountingCommandDraft.FeeRecord -> reject(AccountingCommandFailure.Validation)
        }
        val fingerprint = AccountingCommandFingerprint()
        val declaredAuthorization: (Connection, AuthenticatedStaff) -> Unit = { c, actor ->
            if (command is AccountingCommandDraft.CashSessionOpen) {
                val candidate = CashSession(0, command.terminalId, command.cashierId, Instant.EPOCH, command.openingCash)
                authorize(actor, AccountingCommandKind.CASH_SESSION_OPEN, candidate, command.reason)
                requireEligibleOwner(c, command.cashierId)
            }
        }
        mutate(staff, scope, fingerprint.hash(staff.id.value, command), { actorId -> fingerprint.hash(actorId, command) }, declaredAuthorization) { c, actor, cash, sale, hash ->
            when (command) {
                is AccountingCommandDraft.CashSessionOpen -> open(c, actor, command, hash)
                is AccountingCommandDraft.PaymentCapture -> capture(c, actor, requireNotNull(cash), requireNotNull(sale), command, hash)
                is AccountingCommandDraft.PaymentReverse -> reverse(c, actor, requireNotNull(cash), requireNotNull(sale), command, hash)
                is AccountingCommandDraft.ExpenseRecord -> expense(c, actor, requireNotNull(cash), command, hash)
                is AccountingCommandDraft.CashSessionClose -> close(c, actor, requireNotNull(cash), command, hash)
                is AccountingCommandDraft.FeeRecord -> reject(AccountingCommandFailure.Validation)
            }
        }
    }

    override fun recordFee(staff: AuthenticatedStaff, command: PaidFeeCommand): AccountingMutationOutcome = outcome(staff) {
        val scope = Scope(command.commandId, AccountingCommandKind.FEE_RECORD, command.cashSessionId, null, command.reason)
        val fingerprint = AccountingCommandFingerprint()
        mutate(staff, scope, fingerprint.feeHash(staff.id.value, command), { actorId -> fingerprint.feeHash(actorId, command) }, { _, _ -> }) { c, actor, cash, _, hash ->
            val session = requireNotNull(cash)
            val at = clock(c)
            val id = writer.reserveAccountingId(c, AccountingIdentityTable.Ledger)
            val receipt = receipt(c, scope, actor, session.id, null, hash, listOf(id), at)
            posting(c, receipt, id, PaymentPostingPolicy().paidFee(command.commandId, command.method, command.amount,
                AccountingEvidence(null, command.reason, command.evidenceRef)), at, nextSequence(c, session.id))
            audit(c, receipt, session.id, command.reason)
            receipt
        }
    }

    override fun findReceipt(staff: AuthenticatedStaff, commandId: UUID): AccountingCommandResult = try {
        transaction { c ->
            val header = receiptHeader(c, commandId) ?: return@transaction AccountingCommandResult.NotFound
            if (header.kind in setOf(AccountingCommandKind.FEE_RECORD, AccountingCommandKind.ADJUSTMENT_RECORD, AccountingCommandKind.COMMERCIAL_RECOGNITION))
                return@transaction AccountingCommandResult.NotFound
            val actor = actor(c, staff.id.value)
            val cash = readCash(c, header.cashSessionId, false)
            receiptAccess.authorize(actor, cash)?.let(::reject)
            AccountingCommandResult.Committed(decodeReceipt(header))
        }
    } catch (error: MutationRejected) {
        denial(staff, error.failure)
        if (error.failure == AccountingCommandFailure.Unavailable) AccountingCommandResult.Unavailable else AccountingCommandResult.NotFound
    }
      catch (_: SQLException) { AccountingCommandResult.Unavailable }

    private fun mutate(staff: AuthenticatedStaff, scope: Scope, hash: String, replayHash: (Long) -> String,
        authorizeDeclared: (Connection, AuthenticatedStaff) -> Unit,
        write: (Connection, AuthenticatedStaff, CashSession?, StoredSale?, String) -> AccountingCommandReceipt): AccountingCommandReceipt = transaction { c ->
        lifecycleFence.lock(c)
        serializationStep.acquire(c, scope.id)
        val saved = receiptHeader(c, scope.id)
        val locked = aggregateStep.lock(c, scope, saved)
        val current = authorityStep.authorize(c, staff, scope, locked.cash, authorizeDeclared)
        replayStep.resolve(c, scope, saved, current, replayHash)?.let { return@transaction it }
        lifecycleStep.requireNewV2(c, locked.cash)
        write(c, current, locked.cash, locked.sale, hash)
    }

    private inner class CommandSerializationStep {
        fun acquire(c: Connection, id: UUID) {
            execute(c, "SELECT pg_advisory_xact_lock(hashtextextended(?,0))", id.toString())
        }
    }

    private inner class AggregateScopeStep {
        fun lock(c: Connection, scope: Scope, saved: ReceiptHeader?): LockedScope {
            val cashId = scope.cashId ?: scope.identity?.let { locateCash(c, it) } ?: saved?.cashSessionId
            val cash = cashId?.let { readCash(c, it, true) }
            val sale = scope.identity?.let { identity ->
                role(c, AccountingJdbcRole.Projection)
                val id = scalar(c, "SELECT id FROM sale_state_projection WHERE operation_id=? AND client_instance_id=? AND device_id=? AND sale_id=? FOR UPDATE",
                    UUID.fromString(identity.operationId), UUID.fromString(identity.clientInstanceId), identity.deviceId, identity.saleId) { it.getLong(1) }
                    ?: reject(AccountingCommandFailure.NotVisible)
                role(c, AccountingJdbcRole.Application)
                durable.read(c, id).also { if (it.saga.cashSessionId != cash?.id || it.saga.quadruple != identity) reject(AccountingCommandFailure.NotVisible) }
            }
            return LockedScope(cash, sale)
        }
    }

    private inner class CurrentAuthorityStep {
        fun authorize(c: Connection, staff: AuthenticatedStaff, scope: Scope, cash: CashSession?,
            authorizeDeclared: (Connection, AuthenticatedStaff) -> Unit): AuthenticatedStaff {
            val current = actor(c, staff.id.value)
            authorizeDeclared(c, current)
            if (scope.kind == AccountingCommandKind.CASH_SESSION_OPEN && cash == null) {
                if (!StaffAuthorizationPolicy().permits(current.role, StaffPermission.CashSessionOpen)) reject(AccountingCommandFailure.Forbidden)
            } else this@JdbcAccountingMutationCommands.authorize(current, scope.kind, cash, scope.reason)
            return current
        }
    }

    private inner class ReceiptReplayStep {
        fun resolve(c: Connection, scope: Scope, saved: ReceiptHeader?, current: AuthenticatedStaff,
            replayHash: (Long) -> String): AccountingCommandReceipt? {
            saved ?: return null
            receiptAccess.authorize(current, readCash(c, saved.cashSessionId, false))?.let(::reject)
            if (saved.kind != scope.kind || saved.payloadHash != replayHash(saved.actorId)) reject(AccountingCommandFailure.PayloadMismatch)
            return decodeReceipt(saved)
        }
    }

    private inner class RuntimeAdmissionStep {
        fun requireNewV2(c: Connection, cash: CashSession?) {
            val lifecycle = scalar(c, "SELECT state FROM accounting_runtime WHERE singleton") { AccountingRuntimeState.fromWire(it.getString(1)) }
            lifecycleAdmission.admit(lifecycle ?: AccountingRuntimeState.Unknown)?.let(::reject)
            if (cash != null && cash.status != CashSessionStatus.OPEN) reject(AccountingCommandFailure.Closed)
        }
    }

    private fun open(c: Connection, actor: AuthenticatedStaff, draft: AccountingCommandDraft.CashSessionOpen, hash: String): AccountingCommandReceipt {
        val at = clock(c)
        val candidate = CashSession(0, draft.terminalId, draft.cashierId, at, draft.openingCash)
        authorize(actor, AccountingCommandKind.CASH_SESSION_OPEN, candidate, draft.reason)
        requireEligibleOwner(c, draft.cashierId)
        val cashId = try {
            writer.insertOpenCashSession(c, draft.terminalId, draft.cashierId, draft.openingCash, at)
        } catch (error: SQLException) {
            if (error.sqlState == "23505" && error.message.orEmpty().let { it.contains("uq_open_cash_session_per_terminal") || it.contains("uq_open_cash_session_per_cashier") })
                throw OpenCashConflict(draft.terminalId, draft.cashierId, draft.reason, error)
            throw error
        }
        val eventId = writer.reserveAccountingId(c, AccountingIdentityTable.Ledger)
        val receipt = receipt(c, Scope(draft.commandId, AccountingCommandKind.CASH_SESSION_OPEN, cashId, null, draft.reason), actor, cashId, null, hash, listOf(eventId), at)
        posting(c, receipt, eventId, CashPostingPolicy().opening(cashId, draft.openingCash), at, 1)
        execute(c, "INSERT INTO cash_accounting_coverage(cash_session_id,coverage,command_id) VALUES(?,'COMPLETE_FROM_OPENING',?)", cashId, draft.commandId)
        audit(c, receipt, cashId, draft.reason)
        return receipt
    }

    /** Coordinates close-specific steps after the shared authority/replay/admission boundary. */
    private fun close(c: Connection, actor: AuthenticatedStaff, cash: CashSession,
        draft: AccountingCommandDraft.CashSessionClose, hash: String): AccountingCommandReceipt {
        requireEligibleOwner(c, cash.cashierId)
        closeTerminality.verify(c, cash.id, durable)
        val coverage = closeCoverage.resolve(c, cash)
        val ledger = closeLedger.load(c, cash.id, coverage)
        val calculated = ReconciliationPolicy().calculate(draft.declaredCash, coverage, ledger.postings)
        val now = clock(c)
        val lowerBound = listOfNotNull(cash.openedAt, ledger.lastOccurredAt).maxOrNull()!!
        val cutoff = if (now > lowerBound) now else lowerBound.plusNanos(1_000)
        val snapshot = CashCloseSnapshot(calculated.declaredCash, calculated.expectedCash, calculated.difference,
            calculated.outcome, coverage, cutoff, ledger.watermark)
        val receipt = receipt(c, Scope(draft.commandId, AccountingCommandKind.CASH_SESSION_CLOSE, cash.id, null, draft.reason),
            actor, cash.id, null, hash, emptyList(), cutoff, closeSnapshot = snapshot)
        role(c, AccountingJdbcRole.Projection)
        writer.closeCashSession(c, cash.id, snapshot.declaredCash, cutoff)
        role(c, AccountingJdbcRole.Application)
        execute(c, """
            INSERT INTO cash_reconciliations(cash_session_id,command_id,actor_id,declared_cash,expected_cash,difference,
                outcome,coverage,cutoff,local_watermark,accounting_version,recorded_at) VALUES(?,?,?,?,?,?,?,?,?,?,2,?)
        """.trimIndent(), cash.id, draft.commandId, actor.id.value, snapshot.declaredCash, snapshot.expectedCash,
            snapshot.difference, snapshot.outcome.wire, snapshot.coverage.wire, cutoff, snapshot.localWatermark, cutoff)
        audit(c, receipt, cash.id, draft.reason)
        return receipt
    }

    private fun capture(c: Connection, actor: AuthenticatedStaff, cash: CashSession, sale: StoredSale,
        draft: AccountingCommandDraft.PaymentCapture, hash: String): AccountingCommandReceipt {
        allowed(PaymentTransitionPolicy(PaymentLedgerSemantics.NetCapturedAndRefunded).capture(sale.saga, paymentLedger(c, sale), draft.method, draft.amount, BigDecimal.ZERO))
        val at = clock(c)
        val paymentId = writer.reserveAccountingId(c, AccountingIdentityTable.Payment)
        val eventId = writer.reserveAccountingId(c, AccountingIdentityTable.Ledger)
        val receipt = receipt(c, Scope(draft.commandId, AccountingCommandKind.PAYMENT_CAPTURE, cash.id, draft.identity, draft.reason), actor, cash.id, sale.projectionId, hash, listOf(eventId), at, paymentId)
        val payment = PaymentRecord(paymentId, draft.method, draft.amount, BigDecimal.ZERO, PaymentStatus.CAPTURED)
        writer.insertAccountingPayment(c, sale.projectionId, payment, draft.commandId)
        posting(c, receipt, eventId, PaymentPostingPolicy().capture(payment), at, nextSequence(c, cash.id), sale.projectionId, paymentId)
        updateSale(c, sale, if (sale.saga.status == SaleStatus.RESERVED) SaleStatus.PAYMENT_CAPTURED else sale.saga.status, at)
        audit(c, receipt, paymentId, draft.reason)
        return receipt
    }

    private fun reverse(c: Connection, actor: AuthenticatedStaff, cash: CashSession, sale: StoredSale,
        draft: AccountingCommandDraft.PaymentReverse, hash: String): AccountingCommandReceipt {
        allowed(PaymentTransitionPolicy(PaymentLedgerSemantics.NetCapturedAndRefunded).reverse(sale.saga, paymentLedger(c, sale), draft.originalPaymentId))
        val original = scalar(c, "SELECT * FROM payments WHERE id=? AND sale_id=? AND accounting_version=2 AND status='CAPTURED'",
            draft.originalPaymentId, sale.projectionId) { PaymentRecord(it.getLong("id"), PaymentMethod.fromWire(it.getString("payment_method")), it.getBigDecimal("amount"), it.getBigDecimal("fee_amount"), PaymentStatus.CAPTURED) }
            ?: reject(AccountingCommandFailure.Validation)
        val originalEvent = scalar(c, "SELECT id FROM cash_ledger_events WHERE payment_id=? AND cash_session_id=? AND accounting_version=2 AND event_type='PAYMENT'",
            original.id, cash.id) { it.getLong(1) } ?: reject(AccountingCommandFailure.Validation)
        if (draft.reason.trim().length < 3 || draft.evidenceRef.trim().length !in 3..200) reject(AccountingCommandFailure.Validation)
        val at = clock(c)
        val paymentId = writer.reserveAccountingId(c, AccountingIdentityTable.Payment)
        val eventId = writer.reserveAccountingId(c, AccountingIdentityTable.Ledger)
        val receipt = receipt(c, Scope(draft.commandId, AccountingCommandKind.PAYMENT_REVERSE, cash.id, draft.identity, draft.reason), actor, cash.id, sale.projectionId, hash, listOf(eventId), at, paymentId)
        val refund = original.copy(id = paymentId, feeAmount = BigDecimal.ZERO, status = PaymentStatus.REFUNDED,
            originalPaymentId = original.id, reason = draft.reason, evidenceRef = draft.evidenceRef, actorId = actor.id.value)
        writer.insertAccountingPayment(c, sale.projectionId, refund, draft.commandId)
        posting(c, receipt, eventId, PaymentPostingPolicy().refund(original, refund, originalEvent), at, nextSequence(c, cash.id), sale.projectionId, paymentId)
        updateSale(c, sale, sale.saga.status, at)
        audit(c, receipt, paymentId, draft.reason)
        return receipt
    }

    private fun expense(c: Connection, actor: AuthenticatedStaff, cash: CashSession, draft: AccountingCommandDraft.ExpenseRecord, hash: String): AccountingCommandReceipt {
        if (draft.reason.length > 500) reject(AccountingCommandFailure.Validation)
        val at = clock(c)
        val instruction = draft.instruction
        val existing = instruction as? ExpenseInstruction.SettleExisting
        val amount: BigDecimal
        val expenseId: Long
        val method: PaymentMethod
        if (existing != null) {
            amount = scalar(c, "SELECT amount FROM expenses e WHERE id=? AND cash_session_id=? AND paid_at IS NULL AND NOT EXISTS(SELECT 1 FROM expense_settlements s WHERE s.expense_id=e.id)", existing.expenseId, cash.id) { it.getBigDecimal(1) }
                ?: reject(AccountingCommandFailure.Validation)
            expenseId = existing.expenseId
            method = existing.method
        } else {
            val category = when (instruction) { is ExpenseInstruction.Accrue -> instruction.category; is ExpenseInstruction.AccrueAndSettle -> instruction.category; else -> error("closed expense instruction") }
            amount = when (instruction) { is ExpenseInstruction.Accrue -> instruction.amount; is ExpenseInstruction.AccrueAndSettle -> instruction.amount; else -> error("closed expense instruction") }
            if (category.length > 80) reject(AccountingCommandFailure.Validation)
            // OTHER classifies an unpaid accrual, never implies a cash disbursement.
            method = if (instruction is ExpenseInstruction.AccrueAndSettle) instruction.method else PaymentMethod.OTHER
            expenseId = writer.insertExpense(c, cash.id, category, amount, draft.reason, method.name, actor.id.value, at)
        }
        val settled = instruction !is ExpenseInstruction.Accrue
        val settlementId = if (settled) writer.reserveAccountingId(c, AccountingIdentityTable.Settlement) else null
        val eventIds = List((if (existing == null) 1 else 0) + (if (settled) 1 else 0)) { writer.reserveAccountingId(c, AccountingIdentityTable.Ledger) }
        val receipt = receipt(c, Scope(draft.commandId, AccountingCommandKind.EXPENSE_RECORD, cash.id, null, draft.reason), actor, cash.id, null, hash, eventIds, at, expenseId = expenseId, settlementId = settlementId)
        var sequence = nextSequence(c, cash.id)
        var index = 0
        if (existing == null) posting(c, receipt, eventIds[index++], LedgerPosting(LedgerEventKind.EXPENSE_ACCRUAL, LedgerComponent.EXPENSE_ACCRUAL, method, amount,
            LedgerOrigin(LedgerOriginKind.EXPENSE, expenseId)), at, sequence++, expenseId = expenseId)
        if (settled) {
            execute(c, "INSERT INTO expense_settlements(id,expense_id,cash_session_id,payment_method,amount,actor_id,command_id,paid_at) OVERRIDING SYSTEM VALUE VALUES(?,?,?,?,?,?,?,?)", settlementId, expenseId, cash.id, method, amount, actor.id.value, draft.commandId, at)
            posting(c, receipt, eventIds[index], LedgerPosting(LedgerEventKind.EXPENSE_PAID, LedgerComponent.EXPENSE_SETTLEMENT, method, amount.negate(),
                LedgerOrigin(LedgerOriginKind.EXPENSE, expenseId)), at, sequence, expenseId = expenseId)
        }
        audit(c, receipt, expenseId, draft.reason)
        return receipt
    }

    private fun receipt(c: Connection, scope: Scope, actor: AuthenticatedStaff, cashId: Long, saleId: Long?, hash: String,
        ids: List<Long>, at: Instant, paymentId: Long? = null, expenseId: Long? = null, settlementId: Long? = null,
        closeSnapshot: CashCloseSnapshot? = null): AccountingCommandReceipt {
        val result = json.writeValueAsString(mapOf("ledgerEventIds" to ids, "saleId" to saleId, "paymentId" to paymentId, "expenseId" to expenseId, "settlementId" to settlementId, "committedAt" to at.toString(),
            "closeSnapshot" to closeSnapshot?.let { mapOf("declaredCash" to it.declaredCash, "expectedCash" to it.expectedCash,
                "difference" to it.difference, "outcome" to it.outcome.wire, "coverage" to it.coverage.wire,
                "cutoff" to it.cutoff.toString(), "localWatermark" to it.localWatermark, "accountingVersion" to it.accountingVersion) }))
        execute(c, "INSERT INTO accounting_command_receipts(command_id,actor_id,command_kind,payload_hash,cash_session_id,sale_id,outcome,result,accounting_version,recorded_at) VALUES(?,?,?,?,?,?,'COMMITTED',?::jsonb,2,?)",
            scope.id, actor.id.value, scope.kind, hash, cashId, saleId, result, at)
        return AccountingCommandReceipt(scope.id, actor.id.value, scope.kind, cashId, hash, ids, at, saleId, paymentId, expenseId, settlementId, closeSnapshot)
    }

    private fun posting(c: Connection, receipt: AccountingCommandReceipt, id: Long, posting: LedgerPosting, at: Instant, sequence: Long,
        saleId: Long? = null, paymentId: Long? = null, expenseId: Long? = null) = writer.insertAccountingPosting(c, id, receipt, posting, at, sequence, saleId, paymentId, expenseId)

    private fun audit(c: Connection, receipt: AccountingCommandReceipt, aggregateId: Long, reason: String?) =
        writer.insertAudit(c, receipt.actorId, receipt.kind.name, when (receipt.kind) {
            AccountingCommandKind.PAYMENT_CAPTURE, AccountingCommandKind.PAYMENT_REVERSE -> "payment"
            AccountingCommandKind.EXPENSE_RECORD -> "expense"
            else -> "cash_session"
        }, aggregateId, "command=${receipt.commandId};${reason ?: ""}")

    private fun receiptHeader(c: Connection, id: UUID): ReceiptHeader? = scalar(c, "SELECT * FROM accounting_command_receipts WHERE command_id=?", id) { row ->
        ReceiptHeader(id, row.getLong("actor_id"), AccountingCommandKind.fromWire(row.getString("command_kind")), row.getLong("cash_session_id"),
            row.getString("payload_hash"), row.getLong("sale_id").takeUnless { row.wasNull() }, row.getString("result"))
    }

    private fun decodeReceipt(header: ReceiptHeader): AccountingCommandReceipt =
      runCatching {
        val result = json.readTree(header.result)
        require(result.path("ledgerEventIds").isArray && result.path("committedAt").isTextual)
        AccountingCommandReceipt(header.id, header.actorId, header.kind, header.cashSessionId,
            header.payloadHash, result.path("ledgerEventIds").map { node -> require(node.isIntegralNumber); node.asLong() }, Instant.parse(result.path("committedAt").asText()),
            result.path("saleId").takeIf { node -> node.isNumber }?.asLong(), result.path("paymentId").takeIf { node -> node.isNumber }?.asLong(),
            result.path("expenseId").takeIf { node -> node.isNumber }?.asLong(), result.path("settlementId").takeIf { node -> node.isNumber }?.asLong(),
            result.path("closeSnapshot").takeIf { it.isObject }?.let { snapshot ->
                fun amount(key: String): BigDecimal? = snapshot.path(key).takeUnless { it.isNull }?.let {
                    require(it.isNumber)
                    MoneyPolicy.normalize(it.decimalValue())
                }
                CashCloseSnapshot(requireNotNull(amount("declaredCash")), amount("expectedCash"), amount("difference"),
                    ReconciliationOutcome.fromWire(snapshot.path("outcome").asText()), AccountingCoverage.fromWire(snapshot.path("coverage").asText()),
                    Instant.parse(snapshot.path("cutoff").asText()), snapshot.path("localWatermark").let { require(it.isIntegralNumber); it.asLong() }, snapshot.path("accountingVersion").asInt())
            })
      }.getOrElse { reject(AccountingCommandFailure.Unavailable) }

    private fun paymentLedger(c: Connection, sale: StoredSale): OperationLedger = OperationLedger.Known(query(c, "SELECT * FROM payments WHERE sale_id=? ORDER BY id", sale.projectionId) {
        PaymentLedgerEntry(sale.saga.quadruple, it.getLong("id"), PaymentMethod.fromWire(it.getString("payment_method")), PaymentStatus.fromWire(it.getString("status")),
            it.getBigDecimal("amount"), it.getBigDecimal("fee_amount"), (it.getObject("original_payment_id") as? Number)?.toLong())
    })

    private fun updateSale(c: Connection, sale: StoredSale, status: SaleStatus, at: Instant) {
        role(c, AccountingJdbcRole.Projection)
        if (execute(c, "UPDATE sale_state_projection SET status=?,version=version+1,updated_at=? WHERE id=? AND version=?", status, at, sale.projectionId, sale.version) != 1)
            reject(AccountingCommandFailure.Validation)
        role(c, AccountingJdbcRole.Application)
    }

    private fun locateCash(c: Connection, identity: OperationQuadruple): Long = scalar(c,
        "SELECT i.cash_session_id FROM sale_state_projection p JOIN sale_intents i ON i.id=p.sale_intent_id WHERE p.operation_id=? AND p.client_instance_id=? AND p.device_id=? AND p.sale_id=?",
        UUID.fromString(identity.operationId), UUID.fromString(identity.clientInstanceId), identity.deviceId, identity.saleId) { it.getLong(1) } ?: reject(AccountingCommandFailure.NotVisible)

    private fun readCash(c: Connection, id: Long, locked: Boolean): CashSession? {
        if (locked) role(c, AccountingJdbcRole.Projection)
        val cash = scalar(c, "SELECT * FROM cash_session_projection WHERE id=?${if (locked) " FOR UPDATE" else ""}", id) {
            CashSession(it.getLong("id"), it.getLong("terminal_id"), it.getLong("cashier_id"), it.getTimestamp("opened_at").toInstant(),
                it.getBigDecimal("opening_cash"), CashSessionStatus.fromWire(it.getString("status")), it.getTimestamp("closed_at")?.toInstant(), it.getBigDecimal("closing_cash_declared"))
        }
        if (locked) role(c, AccountingJdbcRole.Application)
        return cash
    }

    private fun actor(c: Connection, id: Long): AuthenticatedStaff = scalar(c, "SELECT display_name,role_code FROM staff_users WHERE id=? AND active", id) {
        AuthenticatedStaff(StaffUserId(id), it.getString(1), StaffRole.fromWire(it.getString(2)))
    } ?: reject(AccountingCommandFailure.Forbidden)

    private fun requireEligibleOwner(c: Connection, id: Long) {
        val role = scalar(c, "SELECT role_code FROM staff_users WHERE id=? AND active", id) { StaffRole.fromWire(it.getString(1)) }
        if (role != StaffRole.CASHIER) reject(AccountingCommandFailure.NotVisible)
    }

    private fun authorize(actor: AuthenticatedStaff, kind: AccountingCommandKind, cash: CashSession?, reason: String?) {
        if (kind == AccountingCommandKind.FEE_RECORD) {
            if (actor.role != StaffRole.OWNER) reject(AccountingCommandFailure.Forbidden)
            if (cash == null || cash.status == CashSessionStatus.UNKNOWN) reject(AccountingCommandFailure.NotVisible)
            return
        }
        val permission = when (kind) {
            AccountingCommandKind.CASH_SESSION_OPEN -> StaffPermission.CashSessionOpen
            AccountingCommandKind.CASH_SESSION_CLOSE -> StaffPermission.CashSessionClose
            AccountingCommandKind.EXPENSE_RECORD -> StaffPermission.ExpenseRecord
            AccountingCommandKind.PAYMENT_CAPTURE -> StaffPermission.PaymentCapture
            AccountingCommandKind.PAYMENT_REVERSE -> StaffPermission.PaymentReverse
            else -> StaffPermission.Unknown
        }
        if (reason != null && reason.length > 500) reject(AccountingCommandFailure.Validation)
        cashPolicy.authorize(actor, permission, cash, reason)?.let {
            reject(when (it) { CashMutationFailure.NotVisible -> AccountingCommandFailure.NotVisible; CashMutationFailure.Validation -> AccountingCommandFailure.Validation; else -> AccountingCommandFailure.Forbidden })
        }
    }

    private fun allowed(decision: TransitionDecision) { if (decision is TransitionDecision.Denied) reject(AccountingTransitionFailureMapper.translate(decision.reason)) }
    private fun nextSequence(c: Connection, cashId: Long): Long = scalar(c, "SELECT coalesce(max(local_sequence),0)+1 FROM cash_ledger_events WHERE cash_session_id=? AND accounting_version=2", cashId) { it.getLong(1) }!!
    private fun clock(c: Connection): Instant = scalar(c, "SELECT clock_timestamp()") { it.getTimestamp(1).toInstant() }!!
    private fun role(c: Connection, role: AccountingJdbcRole) { c.createStatement().use { it.execute("SET LOCAL ROLE ${role.sqlName}") } }
    private fun <T> transaction(block: (Connection) -> T): T = source.connection.use { c ->
        c.autoCommit = false
        c.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
        try { role(c, AccountingJdbcRole.Application); val result = block(c); c.commit(); result }
        catch (error: Exception) { c.rollback(); throw error }
    }
    private fun outcome(staff: AuthenticatedStaff, block: () -> AccountingCommandReceipt): AccountingMutationOutcome = try { AccountingMutationOutcome.Applied(block()) }
        catch (error: CashCloseRejected) { AccountingMutationOutcome.Rejected(error.failure) }
        catch (error: MutationRejected) { denial(staff,error.failure); AccountingMutationOutcome.Rejected(error.failure) }
        catch (error: OpenCashConflict) {
            val failure = resolveOpenConflict(staff, error)
            denial(staff,failure)
            AccountingMutationOutcome.Rejected(failure)
        }
        catch (_: IllegalArgumentException) { AccountingMutationOutcome.Rejected(AccountingCommandFailure.Validation) }
        catch (_: SQLException) { AccountingMutationOutcome.Rejected(AccountingCommandFailure.Unavailable) }
    /** A denied mutation has already rolled back; only the sanitized denial is persisted separately. */
    private fun denial(staff: AuthenticatedStaff, failure: AccountingCommandFailure) {
        if (failure !in setOf(AccountingCommandFailure.Forbidden,AccountingCommandFailure.NotVisible)) return
        try {
            transaction { c ->
                val exists = scalar(c,"SELECT id FROM staff_users WHERE id=?",staff.id.value) { it.getLong(1) }
                if (exists != null) writer.insertAudit(c,staff.id.value,SecurityAuditEvent.AUTHORIZATION_DENIED.name,"staff_user",staff.id.value,"accounting authority denied")
            }
        } catch (_: SQLException) { /* No success receipt or alternate writer is allowed when audit storage fails. */ }
    }
    /** Unique constraints arbitrate the race; visibility is resolved only after the failed transaction rolled back. */
    private fun resolveOpenConflict(staff: AuthenticatedStaff, conflict: OpenCashConflict): AccountingCommandFailure = try {
        transaction { c ->
            val current = actor(c, staff.id.value)
            val blockers = query(c, "SELECT id FROM cash_session_projection WHERE status='OPEN' AND (terminal_id=? OR cashier_id=?) ORDER BY id",
                conflict.terminalId, conflict.cashierId) { it.getLong(1) }.mapNotNull { readCash(c, it, false) }
            if (blockers.isEmpty() || blockers.any { cashPolicy.authorize(current, StaffPermission.CashSessionOpen, it, conflict.reason) != null })
                AccountingCommandFailure.NotVisible else AccountingCommandFailure.CashSessionConflict
        }
    } catch (_: Exception) { AccountingCommandFailure.Unavailable }
    private fun reject(failure: AccountingCommandFailure): Nothing = throw MutationRejected(failure)
    private fun execute(c: Connection, sql: String, vararg args: Any?): Int = c.prepareStatement(sql).use { s -> bind(s, args); s.execute(); s.updateCount }
    private fun <T> scalar(c: Connection, sql: String, vararg args: Any?, map: (ResultSet) -> T): T? = query(c, sql, *args, map = map).singleOrNull()
    private fun <T> query(c: Connection, sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> = c.prepareStatement(sql).use { s ->
        bind(s, args); s.executeQuery().use { r -> buildList { while (r.next()) add(map(r)) } }
    }
    private fun bind(s: java.sql.PreparedStatement, args: Array<out Any?>) { args.forEachIndexed { i, value -> s.setObject(i + 1, when (value) {
        is Instant -> Timestamp.from(value); is Enum<*> -> value.name; is BigDecimal -> MoneyPolicy.normalize(value); else -> value
    }) } }
    private data class Scope(val id: UUID, val kind: AccountingCommandKind, val cashId: Long?, val identity: OperationQuadruple?, val reason: String?)
    private data class LockedScope(val cash: CashSession?, val sale: StoredSale?)
    private data class ReceiptHeader(val id: UUID, val actorId: Long, val kind: AccountingCommandKind, val cashSessionId: Long,
        val payloadHash: String, val saleId: Long?, val result: String)
    private class MutationRejected(val failure: AccountingCommandFailure) : RuntimeException()
    private class OpenCashConflict(val terminalId: Long, val cashierId: Long, val reason: String?, cause: SQLException) : RuntimeException(cause)
}

private enum class AccountingJdbcRole(val sqlName: String) { Application("blackstore_app"), Projection("blackstore_projection_worker") }
