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

@Component
@ConditionalOnProperty(name = ["blackstore.persistence.enabled"], havingValue = "true")
class JdbcCounterEntryStore(
    private val dataSource: DataSource,
) : CounterEntryStore {
    private val writer = JdbcBlackStoreWriter()

    override fun savePayment(payment: PaymentRecord, operationId: String): PaymentRecord =
        asRole("blackstore_app") { connection ->
            val projectionId = findProjection(connection, operationId)
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
                    payment.actorId ?: error("reversal requires an actor"),
                    "PAYMENT_REVERSED",
                    "payment",
                    id,
                    detail = "original=${payment.originalPaymentId};reason=${payment.reason};evidence=${payment.evidenceRef}",
                )
            }
            payment.copy(id = id)
        }

    override fun findPayment(id: Long): PaymentRecord? =
        asRole("blackstore_app") { connection ->
            connection.prepareStatement(
                "SELECT id, payment_method, amount, fee_amount, status FROM payments WHERE id = ?",
            ).use { statement ->
                statement.setLong(1, id)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) {
                        null
                    } else {
                        PaymentRecord(
                            id = rows.getLong("id"),
                            method = PaymentMethod.valueOf(rows.getString("payment_method")),
                            amount = rows.getBigDecimal("amount"),
                            feeAmount = rows.getBigDecimal("fee_amount"),
                            status = PaymentStatus.valueOf(rows.getString("status")),
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

    private fun findProjection(connection: java.sql.Connection, operationId: String): Long =
        connection.prepareStatement(
            "SELECT id FROM sale_state_projection WHERE operation_id = ?",
        ).use { statement ->
            statement.setObject(1, UUID.fromString(operationId))
            statement.executeQuery().use { rows ->
                check(rows.next()) { "sale $operationId is not persisted" }
                rows.getLong(1)
            }
        }

    private fun sum(connection: java.sql.Connection, sql: String): BigDecimal =
        connection.createStatement().executeQuery(sql).use { rows ->
            rows.next()
            rows.getBigDecimal(1)
        }

    private fun <T> asRole(role: String, block: (java.sql.Connection) -> T): T =
        dataSource.connection.use { connection ->
            connection.createStatement().execute("SET ROLE $role")
            block(connection)
        }
}
