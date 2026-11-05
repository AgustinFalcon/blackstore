package com.blackstore.infrastructure.persistence

import com.blackstore.domain.accounting.*
import com.blackstore.domain.cash.CashSession
import com.blackstore.domain.model.*
import com.blackstore.domain.sales.*
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant

internal class CashCloseRejected(val failure: AccountingCommandFailure) : RuntimeException()

/** The cash lock held by the coordinator excludes all writers that change close eligibility. */
internal class VerifyCashCloseTerminality {
    private val terminalEvidence = TerminalRemoteEvidenceTranslator()
    private val policy = CashCloseTerminalityPolicy()

    fun verify(connection: Connection, cashSessionId: Long, durable: JdbcDurableSaleRepository) {
        val ids = closeQuery(connection, "SELECT p.id FROM sale_intents i LEFT JOIN sale_state_projection p ON p.sale_intent_id=i.id WHERE i.cash_session_id=? ORDER BY i.id", cashSessionId) {
            (it.getObject(1) as? Number)?.toLong()
        }
        for (id in ids) {
            if (id == null) throw CashCloseRejected(AccountingCommandFailure.NonTerminalSale)
            val assessment = runCatching {
                val sale = durable.read(connection, id).saga
                val pending = closeQuery(connection, """
                    SELECT count(*) FROM storecore_outbox_commands o LEFT JOIN storecore_command_delivery d ON d.command_id=o.id
                    WHERE o.client_instance_id=? AND o.device_id=? AND o.sale_id=? AND o.operation_id=?
                    AND (d.state IS NULL OR d.state<>?)
                """.trimIndent(), java.util.UUID.fromString(sale.quadruple.clientInstanceId), sale.quadruple.deviceId,
                    sale.quadruple.saleId, java.util.UUID.fromString(sale.quadruple.operationId), DeliveryState.APPLIED.name) { it.getInt(1) }.single()
                val kind = when (sale.status) {
                    SaleStatus.COMMITTED -> StoreCoreOperationKind.COMMIT
                    SaleStatus.RELEASED -> StoreCoreOperationKind.RELEASE
                    else -> return@runCatching SaleTerminality.Pending
                }
                val command = sale.outbox.singleOrNull { it.kind == kind }
                val receipts = closeQuery(connection, """
                    SELECT e.evidence_json,e.evidence_hash,e.operation_kind,e.state,e.receipt,e.contract_version,e.openapi_digest,
                        e.client_instance_id,e.device_id,e.sale_id,e.operation_id
                    FROM storecore_outbox_commands o JOIN storecore_inbox_applications a ON a.command_id=o.id
                    JOIN storecore_inbox_events e ON e.id=a.inbox_id
                    WHERE o.client_instance_id=? AND o.device_id=? AND o.sale_id=? AND o.operation_id=?
                    AND o.operation_kind=? AND a.state=? AND e.state<>?
                """.trimIndent(), java.util.UUID.fromString(sale.quadruple.clientInstanceId), sale.quadruple.deviceId,
                    sale.quadruple.saleId, java.util.UUID.fromString(sale.quadruple.operationId), kind.name,
                    InboxApplyState.APPLIED.name, StoreCoreOperationState.PENDING.name) { row -> terminalReceipt(row) }.distinct()
                policy.assess(sale, command, receipts.singleOrNull(), pending)
            }.getOrElse { error ->
                if (error is java.sql.SQLException) throw error
                SaleTerminality.Unknown
            }
            if (assessment != SaleTerminality.Proven) throw CashCloseRejected(AccountingCommandFailure.NonTerminalSale)
        }
    }

    private fun terminalReceipt(row: ResultSet): StoreCoreOperationReceipt {
        val receipt = terminalEvidence.translateReceipt(requireNotNull(row.getString("evidence_json")), row.getString("evidence_hash"))
        require(receipt.kind.name == row.getString("operation_kind") && receipt.state.name == row.getString("state") &&
            receipt.receipt == row.getString("receipt") && receipt.contract.contractVersion == row.getString("contract_version") &&
            receipt.contract.openapiDigestSha256 == row.getString("openapi_digest") && receipt.quadruple == OperationQuadruple(
                row.getString("client_instance_id"), row.getString("device_id"), row.getString("sale_id"), row.getString("operation_id")))
        return receipt
    }
}

/** No coverage is manufactured for post-activation sessions. Old sessions receive only a legacy marker. */
internal class ResolveCashCloseCoverage {
    fun resolve(connection: Connection, cash: CashSession): AccountingCoverage {
        val stored = closeQuery(connection, "SELECT coverage FROM cash_accounting_coverage WHERE cash_session_id=?", cash.id) {
            AccountingCoverage.fromWire(it.getString(1))
        }.singleOrNull()
        if (stored != null) {
            if (stored == AccountingCoverage.Unknown) throw CashCloseRejected(AccountingCommandFailure.Unavailable)
            return stored
        }
        val activatedAt = closeQuery(connection, "SELECT accounting_activation_at FROM accounting_runtime WHERE singleton") {
            it.getTimestamp(1)?.toInstant()
        }.singleOrNull() ?: throw CashCloseRejected(AccountingCommandFailure.Unavailable)
        if (!cash.openedAt.isBefore(activatedAt)) throw CashCloseRejected(AccountingCommandFailure.Unavailable)
        connection.prepareStatement("INSERT INTO cash_accounting_coverage(cash_session_id,coverage) VALUES(?,?)").use {
            it.setLong(1, cash.id); it.setString(2, AccountingCoverage.LegacyIncomplete.wire); it.executeUpdate()
        }
        return AccountingCoverage.LegacyIncomplete
    }
}

internal data class CashCloseLedger(val postings: List<LedgerPosting>, val watermark: Long, val lastOccurredAt: Instant?)

/** Translates persisted postings once and checks source completeness before declaring official cash. */
internal class LoadCashCloseLedger {
    fun load(connection: Connection, cashId: Long, coverage: AccountingCoverage): CashCloseLedger {
        val watermark = closeQuery(connection, "SELECT coalesce(max(local_sequence),0),max(occurred_at) FROM cash_ledger_events WHERE cash_session_id=? AND accounting_version=2", cashId) {
            it.getLong(1) to it.getTimestamp(2)?.toInstant()
        }.single()
        if (coverage == AccountingCoverage.LegacyIncomplete) return CashCloseLedger(emptyList(), watermark.first, watermark.second)
        val missing = closeQuery(connection, """
            SELECT EXISTS(SELECT 1 FROM cash_ledger_events WHERE cash_session_id=? AND accounting_version IS NULL)
            OR EXISTS(SELECT 1 FROM payments p JOIN sale_state_projection s ON s.id=p.sale_id JOIN sale_intents i ON i.id=s.sale_intent_id
                WHERE i.cash_session_id=? AND (p.accounting_version IS DISTINCT FROM 2 OR NOT EXISTS(
                    SELECT 1 FROM cash_ledger_events l WHERE l.payment_id=p.id AND l.accounting_version=2)))
            OR EXISTS(SELECT 1 FROM expenses e WHERE e.cash_session_id=? AND (e.paid_at IS NOT NULL OR NOT EXISTS(
                SELECT 1 FROM cash_ledger_events l WHERE l.expense_id=e.id AND l.event_type=? AND l.accounting_version=2)))
            OR EXISTS(SELECT 1 FROM expense_settlements s WHERE s.cash_session_id=? AND NOT EXISTS(
                SELECT 1 FROM cash_ledger_events l WHERE l.expense_id=s.expense_id AND l.event_type=? AND l.accounting_version=2))
        """.trimIndent(), cashId, cashId, cashId, LedgerEventKind.EXPENSE_ACCRUAL.name, cashId, LedgerEventKind.EXPENSE_PAID.name) { it.getBoolean(1) }.single()
        if (missing) throw CashCloseRejected(AccountingCommandFailure.Unavailable)
        val postings = try {
            closeQuery(connection, "SELECT * FROM cash_ledger_events WHERE cash_session_id=? AND accounting_version=2 ORDER BY local_sequence", cashId) {
                val original = (it.getObject("original_event_id") as? Number)?.toLong()
                val reason = it.getString("reason")
                val reference = it.getString("evidence_ref")
                LedgerPosting(LedgerEventKind.fromWire(it.getString("event_type")), LedgerComponent.fromWire(it.getString("component")),
                    PaymentMethod.fromWire(it.getString("payment_method")), it.getBigDecimal("amount_delta"),
                    LedgerOrigin(LedgerOriginKind.fromWire(it.getString("origin_kind")), it.getString("origin_id")),
                    if (reason != null && reference != null) AccountingEvidence(original, reason, reference) else null)
            }
        } catch (_: IllegalArgumentException) { throw CashCloseRejected(AccountingCommandFailure.Unavailable) }
        if (postings.count { it.kind == LedgerEventKind.OPENING } != 1) throw CashCloseRejected(AccountingCommandFailure.Unavailable)
        return CashCloseLedger(postings, watermark.first, watermark.second)
    }
}

private fun <T> closeQuery(connection: Connection, sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> =
    connection.prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(map(rows)) } }
    }

private enum class InboxApplyState { APPLIED }
