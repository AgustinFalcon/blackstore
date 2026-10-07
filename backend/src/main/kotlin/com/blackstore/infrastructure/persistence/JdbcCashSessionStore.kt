package com.blackstore.infrastructure.persistence

import com.blackstore.domain.cash.CashSession
import com.blackstore.domain.cash.CashSessionStatus
import com.blackstore.domain.port.out.cash.CashSessionStore
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.sql.ResultSet
import javax.sql.DataSource

@Component
@ConditionalOnProperty(name = ["blackstore.persistence.enabled"], havingValue = "true")
class JdbcCashSessionStore(
    private val dataSource: DataSource,
) : CashSessionStore {
    override fun list(): List<CashSession> =
        asRole("blackstore_app") { connection ->
            connection.createStatement().executeQuery(
                """
                SELECT id, terminal_id, cashier_id, opened_at, opening_cash, status, closed_at, closing_cash_declared
                FROM cash_session_projection
                ORDER BY id
                """.trimIndent(),
            ).use { rows ->
                val sessions = mutableListOf<CashSession>()
                while (rows.next()) sessions += rows.toSession()
                sessions
            }
        }

    private fun <T> asRole(role: String, block: (java.sql.Connection) -> T): T =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { it.execute("SET LOCAL ROLE $role") }
                val result = block(connection)
                connection.commit()
                result
            } catch (error: Exception) { connection.rollback(); throw error }
        }

    private fun ResultSet.toSession(): CashSession =
        CashSession(
            id = getLong("id"),
            terminalId = getLong("terminal_id"),
            cashierId = getLong("cashier_id"),
            openedAt = getTimestamp("opened_at").toInstant(),
            openingCash = getBigDecimal("opening_cash"),
            status = CashSessionStatus.fromWire(getString("status")),
            closedAt = getTimestamp("closed_at")?.toInstant(),
            closingCashDeclared = getBigDecimal("closing_cash_declared"),
        )
}
