package com.blackstore.infrastructure.persistence

import com.blackstore.domain.ledger.ExpenseRecord
import com.blackstore.domain.port.out.counter.CounterEntryStore
import com.blackstore.domain.reports.ShiftFigures
import com.blackstore.domain.sales.PaymentMethod
import com.blackstore.domain.sales.PaymentRecord
import com.blackstore.domain.sales.PaymentStatus
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.UUID
import javax.sql.DataSource
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.sales.OperationLedger
import com.blackstore.domain.sales.PaymentLedgerEntry
import com.blackstore.domain.sales.MoneyPolicy
import com.blackstore.domain.sales.PaymentTransitionPolicy

@Component
@ConditionalOnProperty(name = ["blackstore.persistence.enabled"], havingValue = "true")
class JdbcCounterEntryStore(
    private val dataSource: DataSource,
) : CounterEntryStore {
    private val writer = JdbcBlackStoreWriter()
    private val durable = JdbcDurableSaleRepository(dataSource)
    private val sales = JdbcSaleRecordStore(dataSource)

    override fun savePayment(payment: PaymentRecord, operationId: String): PaymentRecord {
        val identity = asRole("blackstore_app") { connection -> resolveIdentity(connection, operationId) }
        return savePayment(payment, identity)
    }

    override fun savePayment(payment: PaymentRecord, identity: OperationQuadruple): PaymentRecord =
        asRole("blackstore_app") { connection ->
            MoneyPolicy.normalize(payment.amount)
            MoneyPolicy.normalize(payment.feeAmount)
            val stored=durable.lock(connection,identity)
            require(stored.saga.quadruple==identity)
            val projectionId = findProjection(connection, identity)
            val trustedActor = payment.actorId ?: error("trusted payment actor is required")
            sales.authorize(connection,stored.saga,trustedActor,payment.reason)
            val ledger=sales.ledger(connection,stored.saga)
            when(payment.status) {
                PaymentStatus.CAPTURED -> PaymentTransitionPolicy().capture(stored.saga,ledger,payment.method,payment.amount,payment.feeAmount).assertAllowed()
                PaymentStatus.REFUNDED -> PaymentTransitionPolicy().reverse(stored.saga,ledger,requireNotNull(payment.originalPaymentId)).assertAllowed()
                else -> error("unsupported durable payment mutation")
            }
            if (payment.originalPaymentId != null) {
                connection.prepareStatement("SELECT sale_id, status FROM payments WHERE id = ?").use { statement ->
                    statement.setLong(1, payment.originalPaymentId)
                    statement.executeQuery().use { rows ->
                        require(rows.next() && rows.getLong(1) == projectionId && PaymentStatus.fromWire(rows.getString(2)) == PaymentStatus.CAPTURED) { "original payment identity mismatch" }
                    }
                }
            }
            val id =
                writer.insertPayment(
                    connection,
                    projectionId,
                    payment.method.name,
                    payment.amount,
                    payment.feeAmount,
                    payment.status.name,
                )
            if (payment.status == PaymentStatus.REFUNDED) {
                writer.insertAudit(
                    connection,
                    trustedActor,
                    "PAYMENT_REVERSED",
                    "payment",
                    id,
                    detail = "original=${payment.originalPaymentId};reason=${payment.reason};evidence=${payment.evidenceRef}",
                )
            }
            if (payment.status == PaymentStatus.CAPTURED) {
                writer.insertAudit(connection, trustedActor, com.blackstore.domain.identity.SecurityAuditEvent.PAYMENT_CAPTURED.name, "payment", id)
            }
            sales.bumpVersion(connection,projectionId,stored.version)
            payment.copy(id = id)
        }

    override fun findPayment(id: Long): PaymentRecord? =
        asRole("blackstore_app") { connection ->
            connection.prepareStatement(
                "SELECT id, payment_method, amount, fee_amount, status FROM payments WHERE id = ?",
            ).use { statement ->
                statement.setLong(1, id)
                statement.executeQuery().use rowsUse@ { rows ->
                    if (!rows.next()) {
                        null
                    } else {
                        val method = PaymentMethod.fromWire(rows.getString("payment_method"))
                        val status = PaymentStatus.fromWire(rows.getString("status"))
                        if (method == PaymentMethod.UNKNOWN || status != PaymentStatus.CAPTURED) return@rowsUse null
                        PaymentRecord(
                            id = rows.getLong("id"),
                            method = method,
                            amount = rows.getBigDecimal("amount"),
                            feeAmount = rows.getBigDecimal("fee_amount"),
                            status = status,
                        )
                    }
                }
            }
        }

    override fun saveExpense(expense: ExpenseRecord) {
        asRole("blackstore_app") { connection ->
            writer.insertExpense(
                connection,
                expense.cashSessionId,
                expense.category,
                expense.amount,
                expense.reason,
                expense.paymentMethod.name,
                expense.actorId,
                expense.accruedAt,
            )
        }
    }

    override fun figures(): ShiftFigures =
        asRole("blackstore_app") { connection ->
            val gross = sum(connection, "SELECT COALESCE(SUM(original_unit_price * quantity), 0) FROM sale_lines")
            val discounts = sum(connection, "SELECT COALESCE(SUM(discount_amount * quantity), 0) FROM sale_lines")
            val collected = sum(connection, "SELECT COALESCE(SUM(amount), 0) FROM payments WHERE status = 'CAPTURED'")
            val fees = sum(connection, "SELECT COALESCE(SUM(fee_amount), 0) FROM payments WHERE status = 'CAPTURED'")
            val refunds = sum(connection, "SELECT COALESCE(SUM(amount), 0) FROM payments WHERE status = 'REFUNDED'")
            val expenses = sum(connection, "SELECT COALESCE(SUM(amount), 0) FROM expenses")
            ShiftFigures(gross, discounts, refunds, collected, fees, expenses)
        }

    override fun paymentLedger(identity: OperationQuadruple): OperationLedger =
        asRole("blackstore_app") { connection ->
            val projectionId = findProjection(connection, identity)
            connection.prepareStatement("""
                SELECT p.id, p.payment_method, p.amount, p.fee_amount, p.status,
                    (SELECT MIN(a.payload_redacted->>'detail') FROM audit_events a
                     WHERE a.aggregate_type = 'payment' AND a.aggregate_id = p.id AND a.event_type = 'PAYMENT_REVERSED') AS reversal_detail
                FROM payments p WHERE p.sale_id = ? ORDER BY p.id
            """.trimIndent()).use { statement ->
                statement.setLong(1, projectionId)
                statement.executeQuery().use { rows ->
                    val entries = mutableListOf<PaymentLedgerEntry>()
                    while (rows.next()) {
                        val original = rows.getString("reversal_detail")?.let { Regex("^original=(\\d+);reason=").find(it)?.groupValues?.get(1)?.toLongOrNull() }
                        entries += PaymentLedgerEntry(identity, rows.getLong("id"), PaymentMethod.fromWire(rows.getString("payment_method")), PaymentStatus.fromWire(rows.getString("status")), rows.getBigDecimal("amount"), rows.getBigDecimal("fee_amount"), original)
                    }
                    OperationLedger.Known(entries)
                }
            }
        }

    private fun resolveIdentity(connection: java.sql.Connection, operationId: String): OperationQuadruple =
        connection.prepareStatement(
            "SELECT client_instance_id, device_id, sale_id FROM sale_state_projection WHERE operation_id = ?",
        ).use { statement ->
            statement.setObject(1, UUID.fromString(operationId))
            statement.executeQuery().use { rows ->
                check(rows.next()) { "sale $operationId is not persisted" }
                val identity = OperationQuadruple(rows.getString(1), rows.getString(2), rows.getString(3), operationId)
                check(!rows.next()) { "sale operation is ambiguous" }
                identity
            }
        }

    private fun findProjection(connection: java.sql.Connection, identity: OperationQuadruple): Long =
        connection.prepareStatement("SELECT id, client_instance_id, device_id, sale_id, status FROM sale_state_projection WHERE operation_id = ?").use { statement ->
            statement.setObject(1, UUID.fromString(identity.operationId))
            statement.executeQuery().use { rows ->
                val matches = mutableListOf<Long>()
                while (rows.next()) if (rows.getString(2) == identity.clientInstanceId && rows.getString(3) == identity.deviceId && rows.getString(4) == identity.saleId) {
                    require(com.blackstore.domain.sales.SaleStatus.fromWire(rows.getString(5)) != com.blackstore.domain.sales.SaleStatus.UNKNOWN) { "unknown sale projection" }
                    matches += rows.getLong(1)
                }
                require(matches.size == 1) { "sale identity missing or ambiguous" }
                matches.single()
            }
        }

    private fun sum(connection: java.sql.Connection, sql: String): BigDecimal =
        connection.createStatement().executeQuery(sql).use { rows ->
            rows.next()
            rows.getBigDecimal(1)
        }

    private fun <T> asRole(role: String, block: (java.sql.Connection) -> T): T =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().execute("SET LOCAL ROLE $role")
                val result = block(connection)
                connection.commit()
                result
            } catch (e: Exception) { connection.rollback(); throw e }
        }
}
