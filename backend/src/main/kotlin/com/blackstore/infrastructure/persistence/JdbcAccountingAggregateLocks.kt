package com.blackstore.infrastructure.persistence

import com.blackstore.domain.model.OperationQuadruple
import java.sql.Connection
import java.util.UUID

/** Shared lock protocol for every accounting/commercial writer: cash, then sale, then delivery. */
internal class JdbcAccountingAggregateLocks {
    data class LockedSale(val projectionId: Long, val cashSessionId: Long, val identity: OperationQuadruple)

    fun lockCash(connection: Connection, cashSessionId: Long) {
        role(connection, "blackstore_projection_worker")
        connection.prepareStatement("SELECT id FROM cash_session_projection WHERE id=? FOR UPDATE").use { statement ->
            statement.setLong(1, cashSessionId)
            statement.executeQuery().use { rows -> require(rows.next() && !rows.next()) { "cash session unavailable" } }
        }
        role(connection, "blackstore_app")
    }

    fun lockSale(connection: Connection, operationId: String): LockedSale {
        role(connection, "blackstore_app")
        val located = connection.prepareStatement(
            "SELECT p.id,i.cash_session_id,p.client_instance_id,p.device_id,p.sale_id,p.operation_id " +
                "FROM sale_state_projection p JOIN sale_intents i ON i.id=p.sale_intent_id WHERE p.operation_id=?",
        ).use { statement ->
            statement.setObject(1, UUID.fromString(operationId))
            statement.executeQuery().use { rows ->
                require(rows.next()) { "sale identity missing" }
                val result = LockedSale(rows.getLong(1), rows.getLong(2), OperationQuadruple(
                    rows.getString(3), rows.getString(4), rows.getString(5), rows.getString(6),
                ))
                require(!rows.next()) { "sale identity ambiguous" }
                result
            }
        }
        lockCash(connection, located.cashSessionId)
        role(connection, "blackstore_projection_worker")
        connection.prepareStatement("SELECT id FROM sale_state_projection WHERE id=? FOR UPDATE").use { statement ->
            statement.setLong(1, located.projectionId)
            statement.executeQuery().use { rows -> require(rows.next() && !rows.next()) { "sale unavailable" } }
        }
        role(connection, "blackstore_app")
        val current = locate(connection, located.identity)
        require(current == located) { "sale aggregate link changed" }
        return current
    }

    fun lockSale(connection: Connection, identity: OperationQuadruple): LockedSale =
        lockSale(connection, identity.operationId).also { require(it.identity == identity) { "sale identity mismatch" } }

    fun lockDelivery(connection: Connection, commandId: Long) {
        connection.prepareStatement("SELECT command_id FROM storecore_command_delivery WHERE command_id=? FOR UPDATE").use { statement ->
            statement.setLong(1, commandId)
            statement.executeQuery().use { rows -> require(rows.next() && !rows.next()) { "delivery unavailable" } }
        }
    }

    private fun locate(connection: Connection, identity: OperationQuadruple): LockedSale = connection.prepareStatement(
        "SELECT p.id,i.cash_session_id,p.client_instance_id,p.device_id,p.sale_id,p.operation_id " +
            "FROM sale_state_projection p JOIN sale_intents i ON i.id=p.sale_intent_id " +
            "WHERE p.operation_id=? AND p.client_instance_id=? AND p.device_id=? AND p.sale_id=?",
    ).use { statement ->
        statement.setObject(1, UUID.fromString(identity.operationId))
        statement.setObject(2, UUID.fromString(identity.clientInstanceId))
        statement.setString(3, identity.deviceId)
        statement.setString(4, identity.saleId)
        statement.executeQuery().use { rows ->
            require(rows.next()) { "sale identity missing" }
            val result = LockedSale(rows.getLong(1), rows.getLong(2), OperationQuadruple(
                rows.getString(3), rows.getString(4), rows.getString(5), rows.getString(6),
            ))
            require(!rows.next()) { "sale identity ambiguous" }
            result
        }
    }

    private fun role(connection: Connection, role: String) {
        connection.createStatement().use { it.execute("SET LOCAL ROLE $role") }
    }
}
