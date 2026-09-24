package com.blackstore.infrastructure.persistence

import com.blackstore.domain.port.out.sales.SaleRecordStore
import com.blackstore.domain.model.StoreCoreOperationKind
import com.blackstore.domain.sales.SaleSaga
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.UUID
import javax.sql.DataSource

@Component
@ConditionalOnProperty(name = ["blackstore.persistence.enabled"], havingValue = "true")
class JdbcSaleRecordStore(
    private val dataSource: DataSource,
    private val seed: LocalDatabaseSeed,
) : SaleRecordStore {
    private val writer = JdbcBlackStoreWriter()

    override fun recordIntentAndOutbox(saga: SaleSaga) {
        val clientId = UUID.fromString(saga.quadruple.clientInstanceId)
        val operationId = UUID.fromString(saga.quadruple.operationId)
        val command = saga.outbox.first { it.kind == StoreCoreOperationKind.RESERVE }
        val digest = command.openapiDigest.padEnd(64, '0').take(64)
        asRole("blackstore_app") { connection ->
            val id =
                writer.insertPendingSale(
                    connection = connection,
                    clientInstanceId = clientId,
                    deviceId = saga.quadruple.deviceId,
                    saleId = saga.quadruple.saleId,
                    operationId = operationId,
                    cashSessionId = saga.cashSessionId,
                    createdBy = seed.cashierId,
                    contractVersion = command.contractVersion,
                    openapiDigest = digest,
                )
            writer.insertReserveOutbox(
                connection,
                clientId,
                saga.quadruple.deviceId,
                saga.quadruple.saleId,
                operationId,
                command.contractVersion,
                digest,
                command.requestHash.padEnd(64, '0').take(64),
            )
            writer.insertAudit(connection, seed.cashierId, "INTENT_CREATED", "sale", id)
            saga.lines.forEach { line ->
                writer.insertSaleLine(
                    connection,
                    id,
                    line.sku,
                    line.productName,
                    line.quantity,
                    line.originalUnitPrice,
                    line.discountAmount,
                )
            }
        }
    }

    override fun recordInbox(
        saga: SaleSaga,
        responseHash: String,
        kind: String,
        remoteState: String,
        receipt: String?,
        reservationRef: String?,
    ) {
        val reservationUuid =
            reservationRef?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: reservationRef?.let { UUID.nameUUIDFromBytes(it.toByteArray()) }
        asRole("blackstore_app") { connection ->
            writer.insertInbox(
                connection,
                UUID.fromString(saga.quadruple.clientInstanceId),
                saga.quadruple.deviceId,
                saga.quadruple.saleId,
                UUID.fromString(saga.quadruple.operationId),
                responseHash.padEnd(64, '0').take(64),
                saga.outbox.first().contractVersion,
                saga.outbox.first().openapiDigest.padEnd(64, '0').take(64),
                kind,
                remoteState,
                receipt = if (remoteState == "PENDING") null else receipt,
                reservationRef = if (remoteState == "PENDING") null else reservationUuid,
            )
        }
    }

    override fun recordReconciliationRequired(saga: SaleSaga) {
        val evidence = saga.evidence ?: return
        val reason = saga.reconciliationReason ?: return
        val versions = evidence.acceptedPriceVersions.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
        asRole("blackstore_projection_worker") { connection ->
            writer.markReconciliationRequired(
                connection,
                findProjection(connection, saga),
                reason,
                evidence.reservationRef,
                evidence.receipt,
                evidence.contractVersion,
                evidence.openapiDigest.padEnd(64, '0').take(64),
                versions,
            )
        }
    }

    override fun recordReserved(saga: SaleSaga) {
        val evidence = saga.evidence ?: return
        val digest = evidence.openapiDigest.padEnd(64, '0').take(64)
        asRole("blackstore_projection_worker") { connection ->
            writer.markReserved(
                connection,
                findProjection(connection, saga),
                evidence.reservationRef,
                evidence.receipt,
                evidence.contractVersion,
                digest,
                evidence.expiresAt ?: java.time.Instant.now().plusSeconds(900),
                evidence.acceptedPriceVersions.joinToString(prefix = "[", postfix = "]") { "\"$it\"" },
            )
        }
    }

    override fun recordCommitPending(saga: SaleSaga) {
        persistCommand(saga, StoreCoreOperationKind.COMMIT)
        asRole("blackstore_projection_worker") { connection ->
            val projectionId = findProjection(connection, saga)
            writer.advanceSaleStatus(connection, projectionId, "RESERVED", "PAYMENT_CAPTURED")
            writer.advanceSaleStatus(connection, projectionId, "PAYMENT_CAPTURED", "COMMIT_PENDING")
        }
    }

    override fun recordCommitted(saga: SaleSaga) {
        asRole("blackstore_projection_worker") { connection ->
            val projectionId = findProjection(connection, saga)
            writer.advanceSaleStatus(connection, projectionId, "COMMIT_PENDING", "COMMITTED")
        }
    }

    override fun recordReleasePending(saga: SaleSaga) {
        persistCommand(saga, StoreCoreOperationKind.RELEASE)
        asRole("blackstore_projection_worker") { connection ->
            val projectionId = findProjection(connection, saga)
            val fromReserved = writer.advanceSaleStatus(connection, projectionId, "RESERVED", "RELEASE_PENDING")
            if (fromReserved == 0) {
                writer.advanceSaleStatus(connection, projectionId, "PAYMENT_CAPTURED", "RELEASE_PENDING")
            }
        }
    }

    override fun recordReleased(saga: SaleSaga) {
        asRole("blackstore_projection_worker") { connection ->
            val projectionId = findProjection(connection, saga)
            writer.advanceSaleStatus(connection, projectionId, "RELEASE_PENDING", "RELEASED")
        }
    }

    private fun persistCommand(saga: SaleSaga, kind: StoreCoreOperationKind) {
        val command = saga.outbox.first { it.kind == kind }
        asRole("blackstore_app") { connection ->
            writer.insertOutbox(
                connection,
                UUID.fromString(saga.quadruple.clientInstanceId),
                saga.quadruple.deviceId,
                saga.quadruple.saleId,
                UUID.fromString(saga.quadruple.operationId),
                kind.name,
                command.contractVersion,
                command.openapiDigest.padEnd(64, '0').take(64),
                command.requestHash.padEnd(64, '0').take(64),
            )
        }
    }

    private fun findProjection(connection: java.sql.Connection, saga: SaleSaga): Long =
        connection.prepareStatement(
            "SELECT id FROM sale_state_projection WHERE operation_id = ?",
        ).use { statement ->
            statement.setObject(1, UUID.fromString(saga.quadruple.operationId))
            statement.executeQuery().use { rows ->
                check(rows.next()) { "sale ${saga.quadruple.operationId} is not persisted" }
                rows.getLong(1)
            }
        }

    private fun <T> asRole(role: String, block: (java.sql.Connection) -> T): T =
        dataSource.connection.use { connection ->
            connection.createStatement().execute("SET ROLE $role")
            block(connection)
        }
}
