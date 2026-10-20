package com.blackstore.infrastructure.persistence

import java.math.BigDecimal
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import com.blackstore.domain.sales.MoneyPolicy
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * Writes BlackStore-owned rows. Historical tables are insert-only for blackstore_app.
 * Projection updates run as blackstore_projection_worker. No StoreCore database is involved.
 */
class JdbcBlackStoreWriter {
    private val jsonMapper = jacksonObjectMapper()

    /** Reserving identity values permits an immutable complete receipt before payment's BEFORE trigger. */
    fun reserveAccountingId(connection: Connection, table: AccountingIdentityTable): Long =
        connection.prepareStatement("SELECT nextval(pg_get_serial_sequence(?, 'id'))").use { statement ->
            statement.setString(1, table.sqlName)
            statement.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
        }

    fun insertAccountingPayment(connection: Connection, saleId: Long, payment: com.blackstore.domain.sales.PaymentRecord, commandId: UUID) {
        connection.prepareStatement("""
            INSERT INTO payments(id,sale_id,payment_method,amount,fee_amount,status,original_payment_id,accounting_command_id,accounting_version)
            OVERRIDING SYSTEM VALUE VALUES(?,?,?,?,?,?,?,?,2)
        """.trimIndent()).use { statement ->
            statement.setLong(1,payment.id); statement.setLong(2,saleId); statement.setString(3,payment.method.name)
            statement.setBigDecimal(4,MoneyPolicy.normalize(payment.amount)); statement.setBigDecimal(5,MoneyPolicy.normalize(payment.feeAmount))
            statement.setString(6,payment.status.name); statement.setObject(7,payment.originalPaymentId); statement.setObject(8,commandId)
            statement.executeUpdate()
        }
    }

    fun insertAccountingPosting(connection: Connection, id: Long,
        receipt: com.blackstore.domain.accounting.AccountingCommandReceipt,
        posting: com.blackstore.domain.accounting.LedgerPosting, at: Instant, sequence: Long,
        saleId: Long? = null, paymentId: Long? = null, expenseId: Long? = null) {
        connection.prepareStatement("""
            INSERT INTO cash_ledger_events(id,cash_session_id,event_type,amount_delta,sale_id,expense_id,original_event_id,
                actor_id,reason,evidence_ref,occurred_at,payment_method,payment_id,command_id,origin_kind,origin_id,component,
                accounting_version,evidence_json,local_sequence,recorded_at)
            OVERRIDING SYSTEM VALUE VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,2,?::jsonb,?,?)
        """.trimIndent()).use { statement ->
            val values = listOf(id,receipt.cashSessionId,posting.kind.name,MoneyPolicy.normalize(posting.amount),saleId,expenseId,
                posting.evidence?.originalEventId,receipt.actorId,posting.evidence?.reason,posting.evidence?.reference,Timestamp.from(at),
                posting.method.name,paymentId,receipt.commandId,posting.origin.kind.name,posting.origin.id,posting.component.name,
                jsonMapper.writeValueAsString(mapOf("accountingVersion" to 2,"commandId" to receipt.commandId.toString(),
                    "originalEventId" to posting.evidence?.originalEventId,"reference" to posting.evidence?.reference)),sequence,Timestamp.from(at))
            values.forEachIndexed { index,value -> statement.setObject(index+1,value) }
            statement.executeUpdate()
        }
    }

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
            require(openingCash.signum() >= 0)
            statement.setBigDecimal(4, MoneyPolicy.normalize(openingCash))
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
            require(declared.signum() >= 0)
            statement.setBigDecimal(2, MoneyPolicy.normalize(declared))
            statement.setLong(3, sessionId)
            val updated = statement.executeUpdate()
            if (updated != 1) throw com.blackstore.domain.cash.CashMutationException(com.blackstore.domain.cash.CashMutationFailure.Conflict)
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
        val payload = jsonMapper.writeValueAsString(
            buildMap<String, String> {
                put("source", "blackstore")
                if (detail != null) put("detail", detail)
            },
        )
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
        MoneyPolicy.normalize(originalUnitPrice)
        MoneyPolicy.normalize(discountAmount)
        MoneyPolicy.normalize(originalUnitPrice.subtract(discountAmount).multiply(BigDecimal(quantity)))
        require(quantity > 0 && originalUnitPrice.signum() >= 0 && discountAmount.signum() >= 0 && discountAmount <= originalUnitPrice)
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
    ): Long {
        MoneyPolicy.normalize(amount)
        MoneyPolicy.normalize(feeAmount)
        require(amount.signum() > 0 && feeAmount.signum() >= 0 &&
            com.blackstore.domain.sales.PaymentMethod.fromWire(method) != com.blackstore.domain.sales.PaymentMethod.UNKNOWN &&
            com.blackstore.domain.sales.PaymentStatus.fromWire(status) != com.blackstore.domain.sales.PaymentStatus.UNKNOWN)
        return connection.prepareStatement(
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
    ): Long {
        connection.prepareStatement(
            """
            INSERT INTO expenses (cash_session_id, category, amount, reason, payment_method, accrued_at, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, cashSessionId)
            statement.setString(2, category)
            require(amount.signum() > 0)
            statement.setBigDecimal(3, MoneyPolicy.normalize(amount))
            statement.setString(4, reason)
            statement.setString(5, method)
            statement.setTimestamp(6, Timestamp.from(accruedAt))
            statement.setLong(7, actorId)
            return statement.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
        }
    }

    private fun json(value: String): String = jsonMapper.writeValueAsString(value)
}

enum class AccountingIdentityTable(val sqlName: String) { Payment("payments"), Ledger("cash_ledger_events"), Settlement("expense_settlements") }
