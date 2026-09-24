package com.blackstore.infrastructure.persistence

import java.math.BigDecimal
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Writes BlackStore-owned rows. Historical tables are insert-only for blackstore_app.
 * Projection updates run as blackstore_projection_worker. No StoreCore database is involved.
 */
class JdbcBlackStoreWriter {

    fun insertOpenCashSession(
        connection: Connection,
        terminalId: Long,
        cashierId: Long,
        openingCash: BigDecimal,
        openedAt: Instant,
    ): Long =
        connection.prepareStatement(
            """
            INSERT INTO cash_session_projection (terminal_id, cashier_id, opened_at, opening_cash, status)
            VALUES (?, ?, ?, ?, 'OPEN')
            RETURNING id
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, terminalId)
            statement.setLong(2, cashierId)
            statement.setTimestamp(3, Timestamp.from(openedAt))
            statement.setBigDecimal(4, openingCash)
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getLong(1)
            }
        }

    fun closeCashSession(
        connection: Connection,
        sessionId: Long,
        declared: BigDecimal,
        closedAt: Instant,
    ) {
        connection.prepareStatement(
            """
            UPDATE cash_session_projection
            SET status = 'CLOSED', closed_at = ?, closing_cash_declared = ?
            WHERE id = ? AND status = 'OPEN'
            """.trimIndent(),
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(closedAt))
            statement.setBigDecimal(2, declared)
            statement.setLong(3, sessionId)
            val updated = statement.executeUpdate()
            check(updated == 1) { "cash session $sessionId was not open" }
        }
    }

    fun insertPendingSale(
        connection: Connection,
        clientInstanceId: UUID,
        deviceId: String,
        saleId: String,
        operationId: UUID,
        cashSessionId: Long,
        createdBy: Long,
        contractVersion: String,
        openapiDigest: String,
    ): Long {
        val intentId =
            connection.prepareStatement(
                """
                INSERT INTO sale_intents (
                    client_instance_id, device_id, sale_id, operation_id, cash_session_id, created_by,
                    contract_version, openapi_digest
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, clientInstanceId)
                statement.setString(2, deviceId)
                statement.setString(3, saleId)
                statement.setObject(4, operationId)
                statement.setLong(5, cashSessionId)
                statement.setLong(6, createdBy)
                statement.setString(7, contractVersion)
                statement.setString(8, openapiDigest)
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.getLong(1)
                }
            }
        connection.prepareStatement(
            """
            INSERT INTO sale_state_projection (
                sale_intent_id, client_instance_id, device_id, sale_id, operation_id, aggregate_operation_key,
                status, fiscal_status
            ) VALUES (?, ?, ?, ?, ?, ?, 'PENDING_RESERVATION', 'NOT_CONFIGURED')
            RETURNING id
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, intentId)
            statement.setObject(2, clientInstanceId)
            statement.setString(3, deviceId)
            statement.setString(4, saleId)
            statement.setObject(5, operationId)
            statement.setObject(6, operationId)
            statement.executeQuery().use { rows ->
                rows.next()
                return rows.getLong(1)
            }
        }
    }

    fun insertReserveOutbox(
        connection: Connection,
        clientInstanceId: UUID,
        deviceId: String,
        saleId: String,
        operationId: UUID,
        contractVersion: String,
        openapiDigest: String,
        requestHash: String,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO storecore_outbox_commands (
                client_instance_id, device_id, sale_id, operation_id, operation_kind,
                contract_version, openapi_digest, request_hash, payload_redacted
            ) VALUES (?, ?, ?, ?, 'RESERVE', ?, ?, ?, ?::jsonb)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, clientInstanceId)
            statement.setString(2, deviceId)
            statement.setString(3, saleId)
            statement.setObject(4, operationId)
            statement.setString(5, contractVersion)
            statement.setString(6, openapiDigest)
            statement.setString(7, requestHash)
            statement.setString(8, """{"kind":"RESERVE"}""")
            statement.executeUpdate()
        }
    }

    fun insertOutbox(
        connection: Connection,
        clientInstanceId: UUID,
        deviceId: String,
        saleId: String,
        operationId: UUID,
        kind: String,
        contractVersion: String,
        openapiDigest: String,
        requestHash: String,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO storecore_outbox_commands (
                client_instance_id, device_id, sale_id, operation_id, operation_kind,
                contract_version, openapi_digest, request_hash, payload_redacted
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            ON CONFLICT (client_instance_id, device_id, sale_id, operation_id, operation_kind)
            DO NOTHING
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, clientInstanceId)
            statement.setString(2, deviceId)
            statement.setString(3, saleId)
            statement.setObject(4, operationId)
            statement.setString(5, kind)
            statement.setString(6, contractVersion)
            statement.setString(7, openapiDigest)
            statement.setString(8, requestHash)
            statement.setString(9, """{"kind":${json(kind)}}""")
            statement.executeUpdate()
        }
    }

    fun advanceSaleStatus(
        connection: Connection,
        projectionId: Long,
        fromStatus: String,
        toStatus: String,
    ): Int =
        connection.prepareStatement(
            """
            UPDATE sale_state_projection
            SET status = ?, updated_at = now()
            WHERE id = ? AND status = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, toStatus)
            statement.setLong(2, projectionId)
            statement.setString(3, fromStatus)
            statement.executeUpdate()
        }

    fun markReserved(
        connection: Connection,
        projectionId: Long,
        reservationRef: String,
        receipt: String,
        contractVersion: String,
        openapiDigest: String,
        expiresAt: Instant,
        acceptedPriceVersions: String = """["price-v1"]""",
    ) {
        connection.prepareStatement(
            """
            UPDATE sale_state_projection
            SET status = 'RESERVED',
                storecore_reservation_ref = ?,
                reservation_receipt = ?,
                contract_version = ?,
                openapi_digest = ?,
                accepted_price_versions = ?::jsonb,
                reservation_expires_at = ?,
                updated_at = now()
            WHERE id = ? AND status = 'PENDING_RESERVATION'
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, reservationRef)
            statement.setString(2, receipt)
            statement.setString(3, contractVersion)
            statement.setString(4, openapiDigest)
            statement.setString(5, acceptedPriceVersions)
            statement.setTimestamp(6, Timestamp.from(expiresAt))
            statement.setLong(7, projectionId)
            val updated = statement.executeUpdate()
            check(updated == 1) { "pending sale $projectionId was not updated" }
        }
    }

    fun markReconciliationRequired(
        connection: Connection,
        projectionId: Long,
        reason: String,
        reservationRef: String,
        receipt: String,
        contractVersion: String,
        openapiDigest: String,
        acceptedPriceVersions: String,
    ) {
        connection.prepareStatement(
            """
            UPDATE sale_state_projection
            SET status = 'RECONCILIATION_REQUIRED',
                reconciliation_reason = ?,
                storecore_reservation_ref = ?,
                reservation_receipt = ?,
                contract_version = ?,
                openapi_digest = ?,
                accepted_price_versions = ?::jsonb,
                updated_at = now()
            WHERE id = ? AND status IN ('PENDING_RESERVATION', 'RESERVED', 'PAYMENT_CAPTURED', 'COMMIT_PENDING', 'RELEASE_PENDING')
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, reason)
            statement.setString(2, reservationRef)
            statement.setString(3, receipt)
            statement.setString(4, contractVersion)
            statement.setString(5, openapiDigest)
            statement.setString(6, acceptedPriceVersions)
            statement.setLong(7, projectionId)
            val updated = statement.executeUpdate()
            check(updated == 1) { "sale $projectionId was not reconciled" }
        }
    }

    fun insertAudit(
        connection: Connection,
        actorId: Long,
        eventType: String,
        aggregateType: String,
        aggregateId: Long,
        detail: String? = null,
    ) {
        val payload =
            if (detail == null) {
                """{"source":"blackstore"}"""
            } else {
                """{"source":"blackstore","detail":${json(detail)}}"""
            }
        connection.prepareStatement(
            """
            INSERT INTO audit_events (actor_id, event_type, aggregate_type, aggregate_id, payload_redacted)
            VALUES (?, ?, ?, ?, ?::jsonb)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, actorId)
            statement.setString(2, eventType)
            statement.setString(3, aggregateType)
            statement.setLong(4, aggregateId)
            statement.setString(5, payload)
            statement.executeUpdate()
        }
    }

    fun insertInbox(
        connection: Connection,
        clientInstanceId: UUID,
        deviceId: String,
        saleId: String,
        operationId: UUID,
        responseHash: String,
        contractVersion: String,
        openapiDigest: String,
        kind: String = "RESERVE",
        state: String = "PENDING",
        receipt: String? = null,
        reservationRef: UUID? = null,
    ): Int =
        connection.prepareStatement(
            """
            INSERT INTO storecore_inbox_events (
                client_instance_id, device_id, sale_id, operation_id, operation_kind, state,
                receipt, reservation_ref, response_hash, contract_version, openapi_digest, payload_redacted
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            ON CONFLICT (client_instance_id, device_id, sale_id, operation_id, operation_kind, response_hash)
            DO NOTHING
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, clientInstanceId)
            statement.setString(2, deviceId)
            statement.setString(3, saleId)
            statement.setObject(4, operationId)
            statement.setString(5, kind)
            statement.setString(6, state)
            statement.setString(7, receipt)
            statement.setObject(8, reservationRef)
            statement.setString(9, responseHash)
            statement.setString(10, contractVersion)
            statement.setString(11, openapiDigest)
            statement.setString(12, """{"state":${json(state)},"kind":${json(kind)}}""")
            statement.executeUpdate()
        }

    fun insertSaleLine(
        connection: Connection,
        projectionId: Long,
        sku: String,
        productName: String,
        quantity: Int,
        originalUnitPrice: BigDecimal,
        discountAmount: BigDecimal,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO sale_lines (
                sale_id, sku, product_snapshot, quantity, original_unit_price, discount_amount, effective_unit_price
            ) VALUES (?, ?, ?::jsonb, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, projectionId)
            statement.setString(2, sku)
            statement.setString(3, """{"name":${json(productName)}}""")
            statement.setInt(4, quantity)
            statement.setBigDecimal(5, originalUnitPrice)
            statement.setBigDecimal(6, discountAmount)
            statement.setBigDecimal(7, originalUnitPrice.subtract(discountAmount))
            statement.executeUpdate()
        }
    }

    fun insertPayment(
        connection: Connection,
        projectionId: Long,
        method: String,
        amount: BigDecimal,
        feeAmount: BigDecimal,
        status: String = "CAPTURED",
    ): Long =
        connection.prepareStatement(
            """
            INSERT INTO payments (sale_id, payment_method, amount, fee_amount, status)
            VALUES (?, ?, ?, ?, ?)
            RETURNING id
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, projectionId)
            statement.setString(2, method)
            statement.setBigDecimal(3, amount)
            statement.setBigDecimal(4, feeAmount)
            statement.setString(5, status)
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getLong(1)
            }
        }

    fun insertExpense(
        connection: Connection,
        cashSessionId: Long,
        category: String,
        amount: BigDecimal,
        reason: String,
        method: String,
        actorId: Long,
        accruedAt: Instant,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO expenses (cash_session_id, category, amount, reason, payment_method, accrued_at, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, cashSessionId)
            statement.setString(2, category)
            statement.setBigDecimal(3, amount)
            statement.setString(4, reason)
            statement.setString(5, method)
            statement.setTimestamp(6, Timestamp.from(accruedAt))
            statement.setLong(7, actorId)
            statement.executeUpdate()
        }
    }

    private fun json(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
