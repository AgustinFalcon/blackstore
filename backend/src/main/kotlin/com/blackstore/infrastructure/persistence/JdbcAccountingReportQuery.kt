package com.blackstore.infrastructure.persistence

import com.blackstore.domain.accounting.*
import com.blackstore.domain.cash.*
import com.blackstore.domain.identity.*
import com.blackstore.domain.reports.*
import com.blackstore.domain.sales.PaymentMethod
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.ZoneId
import javax.sql.DataSource

/** All sources and authority are read on the same read-only PostgreSQL snapshot. */
@Component
@ConditionalOnProperty(name = ["blackstore.persistence.enabled"], havingValue = "true")
class JdbcAccountingReportQuery(private val source: DataSource) : AccountingReportQuery {
    override fun read(staff: AuthenticatedStaff, request: AccountingReportRequest): AccountingReportResult = try {
        source.connection.use { c ->
            c.autoCommit = false
            c.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
            c.isReadOnly = true
            try {
                c.createStatement().use { it.execute("SET LOCAL ROLE blackstore_app") }
                val result = snapshot(c, staff, request)
                c.commit()
                result
            } catch (error: Exception) { c.rollback(); throw error }
        }
    } catch (_: IllegalArgumentException) { AccountingReportResult.Rejected(AccountingReportFailure.Validation) }
      catch (_: Exception) { AccountingReportResult.Rejected(AccountingReportFailure.Unavailable) }

    private fun snapshot(c: Connection, staff: AuthenticatedStaff, request: AccountingReportRequest): AccountingReportResult {
        val reject: (AccountingReportFailure) -> AccountingReportResult = { AccountingReportResult.Rejected(it) }
        val current = rows(c, "SELECT role_code,active FROM staff_users WHERE id=?", listOf(staff.id.value)) {
            StaffRole.fromWire(it.getString(1)) to it.getBoolean(2)
        }.singleOrNull() ?: return reject(AccountingReportFailure.Forbidden)
        val permission = when (request.period) {
            is ReportPeriod.Shift -> StaffPermission.ShiftReportRead
            is ReportPeriod.Day -> StaffPermission.DailyReportRead
            ReportPeriod.Unknown -> return reject(AccountingReportFailure.Validation)
        }
        if (!current.second || !StaffAuthorizationPolicy().permits(current.first, permission)) return reject(AccountingReportFailure.Forbidden)
        val (cutoff, snapshot) = rows(c, "SELECT clock_timestamp(),pg_current_snapshot()::text") { instant(it, 1) to it.getString(2) }.single()
        val activation = rows(c, "SELECT accounting_activation_at FROM accounting_runtime WHERE singleton") { it.getTimestamp(1)?.toInstant() }.singleOrNull()
        val sessions = rows(c, "SELECT id,opened_at,closed_at,status FROM cash_session_projection WHERE (?::bigint IS NULL OR id=?) AND (?::bigint IS NULL OR terminal_id=?) AND (?::bigint IS NULL OR cashier_id=?)",
            listOf(request.filters.cashSessionId, request.filters.cashSessionId, request.filters.terminalId, request.filters.terminalId, request.filters.cashierId, request.filters.cashierId)) {
            Session(it.getLong(1), instant(it, 2), it.getTimestamp(3)?.toInstant(), CashSessionStatus.fromWire(it.getString(4)))
        }
        if (request.filters.cashSessionId != null && sessions.none { it.id == request.filters.cashSessionId })
            return reject(AccountingReportFailure.NotVisible)
        val shift = (request.period as? ReportPeriod.Shift)?.let { period -> sessions.singleOrNull { it.id == period.sessionId && it.status != CashSessionStatus.UNKNOWN } ?: return reject(AccountingReportFailure.NotVisible) }
        val zones = rows(c, "SELECT zone_id,version,effective_at FROM accounting_zone_versions ORDER BY effective_at DESC") {
            EffectiveReportZone(ZoneId.of(it.getString(1)), it.getString(2), instant(it, 3))
        }
        val start = when (val period = request.period) {
            is ReportPeriod.Day -> period.localDate.atStartOfDay(period.zone).toInstant()
            is ReportPeriod.Shift -> requireNotNull(shift).opened
            ReportPeriod.Unknown -> return reject(AccountingReportFailure.Validation)
        }
        val zone = zones.firstOrNull { it.effectiveAt <= start } ?: return reject(AccountingReportFailure.Unavailable)
        val bounds = when (val period = request.period) {
            is ReportPeriod.Day -> ReportPeriodResolver().day(period, zone)
            is ReportPeriod.Shift -> ReportPeriodResolver().shift(period, requireNotNull(shift).id, shift.opened, shift.closed, cutoff)
            ReportPeriod.Unknown -> return reject(AccountingReportFailure.Validation)
        }
        val activityIds = rows(c, "SELECT cash_session_id FROM commercial_recognitions WHERE occurred_at>=? AND occurred_at<? UNION SELECT i.cash_session_id FROM sale_state_projection s JOIN sale_intents i ON i.id=s.sale_intent_id WHERE s.updated_at>=? AND s.updated_at<? UNION SELECT i.cash_session_id FROM payments p JOIN sale_state_projection s ON s.id=p.sale_id JOIN sale_intents i ON i.id=s.sale_intent_id WHERE p.created_at>=? AND p.created_at<? UNION SELECT cash_session_id FROM expenses WHERE accrued_at>=? AND accrued_at<? OR paid_at>=? AND paid_at<? UNION SELECT cash_session_id FROM expense_settlements WHERE paid_at>=? AND paid_at<?",
            List(6) { listOf(bounds.start, minOf(bounds.endExclusive, cutoff)) }.flatten()) { it.getLong(1) }.toSet()
        val ids = if (shift != null) listOf(shift.id) else sessions.filter { bounds.intersects(it.opened, it.closed) || it.id in activityIds }.map { it.id }
        val scope = Scope(ids, bounds, cutoff)
        val coverage = sources(c, scope)
        val movements = rows(c, "SELECT event_type,payment_method,amount_delta FROM cash_ledger_events WHERE ${scope.predicate} AND accounting_version=2", scope.values) {
            ReportMovement(LedgerEventKind.fromWire(it.getString(1)), PaymentMethod.fromWire(it.getString(2)), it.getBigDecimal(3))
        }
        val unknown = movements.any { it.kind == LedgerEventKind.Unknown || it.method == PaymentMethod.UNKNOWN }
        val completeness = coverage.mapValues { (_, sources) -> ReportCoveragePolicy().assess(
            if (unknown) sources.copy(unknownSources = sources.unknownSources + 1, relevantSources = sources.relevantSources + 1) else sources, bounds, activation) }
        val commercial = rows(c, "SELECT gross_sales,discounts FROM commercial_recognitions WHERE ${scope.predicate}", scope.values) { it.getBigDecimal(1) to it.getBigDecimal(2) }
        val gross = commercial.fold(BigDecimal("0.00")) { value, row -> value + row.first }
        val discounts = commercial.fold(BigDecimal("0.00")) { value, row -> value + row.second }
        val totals = FinancialTotalsPolicy().totals(movements)
        val combined = combine(listOf(requireNotNull(completeness[AccountingMetric.NetSales]), requireNotNull(completeness[AccountingMetric.FeesPaid]), requireNotNull(completeness[AccountingMetric.ExpensesPaid])))
        val formula = ReportFormula().evaluate(request.formula, request.version, AccountingFormulaInputs(gross - discounts,
            totals.values.fold(BigDecimal.ZERO) { sum, row -> sum + row.feesPaid }, totals.values.fold(BigDecimal.ZERO) { sum, row -> sum + row.expensesPaid }, combined))
        val reconciliation = shift?.let { session ->
            rows(c, "SELECT expected_cash,declared_cash,difference,outcome,local_watermark FROM cash_reconciliations WHERE cash_session_id=?", listOf(session.id)) {
                ReportReconciliation(it.getBigDecimal(1), it.getBigDecimal(2), it.getBigDecimal(3), ReconciliationOutcome.fromWire(it.getString(4)), it.getLong(5))
            }.singleOrNull() ?: ReportReconciliation(if (session.closed == null) ReportExpectedCashPolicy().expected(movements, completeness.values) else null,
                null, null, ReconciliationOutcome.Unavailable, null)
        }
        val commercialAvailable = completeness[AccountingMetric.NetSales]?.state in setOf(DataCompleteness.Complete, DataCompleteness.Partial)
        val provisional = when (request.period) {
            is ReportPeriod.Shift -> requireNotNull(shift).closed == null
            is ReportPeriod.Day -> bounds.endExclusive > cutoff
            ReportPeriod.Unknown -> true
        }
        return AccountingReportResult.Available(AccountingReport(request.period, bounds, cutoff, snapshot, zone, 2,
            provisional, gross.takeIf { commercialAvailable }, discounts.takeIf { commercialAvailable },
            (gross - discounts).takeIf { commercialAvailable }, totals, completeness + (AccountingMetric.Contribution to combined), formula, reconciliation))
    }

    private fun sources(c: Connection, scope: Scope): Map<AccountingMetric, ReportSourceCoverage> {
        val sessionEvidence = rows(c, "SELECT coverage FROM cash_accounting_coverage WHERE cash_session_id IN (${scope.idsSql})", scope.ids) { AccountingCoverage.fromWire(it.getString(1)) }
        val base = ReportSourceCoverage(scope.ids.size, sessionEvidence.count { it == AccountingCoverage.CompleteFromOpening },
            scope.ids.size - sessionEvidence.count { it != AccountingCoverage.LegacyIncomplete }, sessionEvidence.count { it == AccountingCoverage.Unknown })
        fun evidence(sql: String): ReportSourceCoverage {
            val flags = rows(c, sql, scope.values) { it.getBoolean(1) }
            return base.copy(relevantSources = base.relevantSources + flags.size, evidencedSources = base.evidencedSources + flags.count { it }, legacySources = base.legacySources + flags.count { !it })
        }
        val net = evidence("SELECT EXISTS(SELECT 1 FROM commercial_recognitions r WHERE r.sale_id=s.id) FROM sale_state_projection s JOIN sale_intents i ON i.id=s.sale_intent_id WHERE ${scope.predicate.replace("occurred_at", "s.updated_at").replace("cash_session_id", "i.cash_session_id")} AND s.status='COMMITTED'")
        val payment = evidence("SELECT p.accounting_version=2 AND EXISTS(SELECT 1 FROM cash_ledger_events l WHERE l.payment_id=p.id AND l.accounting_version=2) FROM payments p JOIN sale_state_projection s ON s.id=p.sale_id JOIN sale_intents i ON i.id=s.sale_intent_id WHERE ${scope.predicate.replace("occurred_at", "p.created_at").replace("cash_session_id", "i.cash_session_id")} AND p.status IN ('CAPTURED','REFUNDED')")
        val expenses = evidence("SELECT EXISTS(SELECT 1 FROM cash_ledger_events l WHERE l.expense_id=e.id AND l.accounting_version=2 AND l.event_type='EXPENSE_ACCRUAL') FROM expenses e WHERE ${scope.predicate.replace("occurred_at", "e.accrued_at").replace("cash_session_id", "e.cash_session_id")}")
        val paidExpenses = evidence("SELECT EXISTS(SELECT 1 FROM expense_settlements s JOIN cash_ledger_events l ON l.expense_id=s.expense_id AND l.command_id=s.command_id AND l.event_type='EXPENSE_PAID' WHERE s.expense_id=e.id) FROM expenses e WHERE ${scope.predicate.replace("occurred_at", "e.paid_at").replace("cash_session_id", "e.cash_session_id")}")
        val settlements = evidence("SELECT EXISTS(SELECT 1 FROM cash_ledger_events l WHERE l.expense_id=s.expense_id AND l.command_id=s.command_id AND l.event_type='EXPENSE_PAID' AND l.accounting_version=2) FROM expense_settlements s WHERE ${scope.predicate.replace("occurred_at", "s.paid_at").replace("cash_session_id", "s.cash_session_id")}")
        val combinedExpenses = ReportSourceCoverage(expenses.relevantSources + paidExpenses.relevantSources + settlements.relevantSources - 2 * base.relevantSources,
            expenses.evidencedSources + paidExpenses.evidencedSources + settlements.evidencedSources - 2 * base.evidencedSources,
            expenses.legacySources + paidExpenses.legacySources + settlements.legacySources - 2 * base.legacySources,
            expenses.unknownSources + paidExpenses.unknownSources + settlements.unknownSources - 2 * base.unknownSources)
        return mapOf(AccountingMetric.NetSales to net, AccountingMetric.Collected to payment, AccountingMetric.Refunds to payment,
            AccountingMetric.FeesPaid to base, AccountingMetric.ExpensesPaid to combinedExpenses)
    }
    private fun combine(values: List<MetricCompleteness>): MetricCompleteness {
        val causes = values.flatMap { it.causes }.toSet()
        return if (causes.isEmpty()) MetricCompleteness.Complete else MetricCompleteness(
            if (values.any { it.state == DataCompleteness.Complete || it.state == DataCompleteness.Partial }) DataCompleteness.Partial else DataCompleteness.LegacyIncomplete, causes)
    }
    private data class Session(val id: Long, val opened: Instant, val closed: Instant?, val status: CashSessionStatus)
    private class Scope(val ids: List<Long>, bounds: ReportBounds, cutoff: Instant) {
        val idsSql = if (ids.isEmpty()) "NULL" else ids.joinToString(",") { "?" }
        val predicate = "cash_session_id IN ($idsSql) AND occurred_at>=? AND occurred_at<? AND occurred_at<?"
        val values = ids + listOf(bounds.start, bounds.endExclusive, cutoff)
    }
    private fun instant(rows: ResultSet, column: Int): Instant = rows.getTimestamp(column).toInstant()
    private fun <T> rows(c: Connection, sql: String, values: List<Any?> = emptyList(), map: (ResultSet) -> T): List<T> = c.prepareStatement(sql).use { statement ->
        values.forEachIndexed { index, value -> statement.setObject(index + 1, if (value is Instant) Timestamp.from(value) else value) }
        statement.executeQuery().use { result -> buildList { while (result.next()) add(map(result)) } }
    }
}
