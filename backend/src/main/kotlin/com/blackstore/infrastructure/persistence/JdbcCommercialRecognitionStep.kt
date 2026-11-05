package com.blackstore.infrastructure.persistence

import com.blackstore.domain.accounting.AccountingCommandKind
import com.blackstore.domain.accounting.CommercialRecognitionPolicy
import com.blackstore.domain.model.StoreCoreOperationReceipt
import com.blackstore.domain.sales.ClaimedSaleCommand
import com.blackstore.domain.sales.SaleSaga
import com.blackstore.domain.sales.StoredSale
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.sql.Connection
import java.sql.Timestamp
import java.util.UUID

/** Persists the ACTIVE-only commercial fact after delivery becomes APPLIED. */
internal class JdbcCommercialRecognitionStep {
    private val mapper = DurableCommandMapper()
    private val json = jacksonObjectMapper()
    private val writer = JdbcBlackStoreWriter()

    fun record(connection: Connection, stored: StoredSale, committed: SaleSaga, claim: ClaimedSaleCommand,
        receipt: StoreCoreOperationReceipt) {
        val pending = connection.prepareStatement("""
            SELECT count(*) FROM storecore_outbox_commands o
            LEFT JOIN storecore_command_delivery d ON d.command_id=o.id
            WHERE o.client_instance_id=? AND o.device_id=? AND o.sale_id=? AND o.operation_id=?
            AND (d.state IS NULL OR d.state<>'APPLIED')
        """.trimIndent()).use { statement ->
            statement.setObject(1, UUID.fromString(committed.quadruple.clientInstanceId))
            statement.setString(2, committed.quadruple.deviceId)
            statement.setString(3, committed.quadruple.saleId)
            statement.setObject(4, UUID.fromString(committed.quadruple.operationId))
            statement.executeQuery().use { rows -> check(rows.next()); rows.getInt(1) }
        }
        val at = connection.createStatement().executeQuery("SELECT clock_timestamp()").use { rows ->
            check(rows.next()); rows.getTimestamp(1).toInstant()
        }
        val recognition = CommercialRecognitionPolicy().recognize(committed, receipt, pending, at)
        // Internal facts never share a predictable identifier with client-supplied command IDs.
        val recognitionId = UUID.randomUUID()
        val payload = listOf(committed.quadruple.clientInstanceId, committed.quadruple.deviceId,
            committed.quadruple.saleId, committed.quadruple.operationId, recognition.grossSales.toPlainString(),
            recognition.discounts.toPlainString(), claim.id.toString()).joinToString("|")
        val result = json.writeValueAsString(mapOf("saleId" to stored.projectionId,
            "grossSales" to recognition.grossSales.toPlainString(), "discounts" to recognition.discounts.toPlainString(),
            "recognizedAt" to recognition.recognizedAt.toString()))
        connection.prepareStatement("""
            INSERT INTO accounting_command_receipts(command_id,actor_id,command_kind,payload_hash,cash_session_id,sale_id,
                outcome,result,accounting_version,recorded_at) VALUES(?,?,?,?,?,?,'COMMITTED',?::jsonb,2,?)
        """.trimIndent()).use { statement ->
            statement.setObject(1, recognitionId); statement.setLong(2, claim.actorId)
            statement.setString(3, AccountingCommandKind.COMMERCIAL_RECOGNITION.name)
            statement.setString(4, mapper.hash(payload)); statement.setLong(5, stored.saga.cashSessionId)
            statement.setLong(6, stored.projectionId); statement.setString(7, result)
            statement.setTimestamp(8, Timestamp.from(at)); statement.executeUpdate()
        }
        connection.prepareStatement("""
            INSERT INTO commercial_recognitions(sale_id,cash_session_id,command_id,actor_id,gross_sales,discounts,
                occurred_at,accounting_version,recorded_at) VALUES(?,?,?,?,?,?,?,2,?)
        """.trimIndent()).use { statement ->
            statement.setLong(1, stored.projectionId); statement.setLong(2, stored.saga.cashSessionId)
            statement.setObject(3, recognitionId); statement.setLong(4, claim.actorId)
            statement.setBigDecimal(5, recognition.grossSales); statement.setBigDecimal(6, recognition.discounts)
            statement.setTimestamp(7, Timestamp.from(at)); statement.setTimestamp(8, Timestamp.from(at))
            statement.executeUpdate()
        }
        writer.insertAudit(connection, claim.actorId, "COMMERCIAL_RECOGNIZED", "sale", stored.projectionId)
    }
}
