package com.blackstore.infrastructure.persistence

import com.blackstore.domain.cash.CashAuditEvent
import com.blackstore.domain.cash.CashSession
import com.blackstore.domain.cash.CashSessionStatus
import com.blackstore.domain.port.out.cash.CashSessionStore
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.sql.ResultSet
import javax.sql.DataSource

@Component
@ConditionalOnProperty(name = ["blackstore.persistence.enabled"], havingValue = "true")
class JdbcCashSessionStore(
    private val dataSource: DataSource,
) : CashSessionStore {
    private val writer = JdbcBlackStoreWriter()

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

    override fun save(session: CashSession): CashSession =
        if (session.id == 0L) {
            val id =
                asRole("blackstore_app") { connection ->
                    writer.insertOpenCashSession(
                        connection,
                        session.terminalId,
                        session.cashierId,
                        session.openingCash,
                        session.openedAt,
                    )
                }
            session.copy(id = id)
        } else if (session.status != CashSessionStatus.OPEN) {
            asRole("blackstore_projection_worker") { connection ->
                writer.closeCashSession(
                    connection,
                    session.id,
                    session.closingCashDeclared ?: BigDecimal.ZERO,
                    session.closedAt ?: session.openedAt,
                )
            }
            session
        } else {
            session
        }

    override fun appendClosureAudit(event: CashAuditEvent) {
        asRole("blackstore_app") { connection ->
            writer.insertAudit(
                connection,
                event.actorId,
                event.eventType,
                "cash_session",
                event.sessionId,
                detail = event.reason,
            )
        }
    }

    private fun <T> asRole(role: String, block: (java.sql.Connection) -> T): T =
        dataSource.connection.use { connection ->
            connection.createStatement().execute("SET ROLE $role")
            block(connection)
        }

    private fun ResultSet.toSession(): CashSession =
        CashSession(
            id = getLong("id"),
            terminalId = getLong("terminal_id"),
            cashierId = getLong("cashier_id"),
            openedAt = getTimestamp("opened_at").toInstant(),
            openingCash = getBigDecimal("opening_cash"),
            status = CashSessionStatus.valueOf(getString("status")),
            closedAt = getTimestamp("closed_at")?.toInstant(),
            closingCashDeclared = getBigDecimal("closing_cash_declared"),
        )
}
