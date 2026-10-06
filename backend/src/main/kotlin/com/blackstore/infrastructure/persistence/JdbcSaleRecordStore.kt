package com.blackstore.infrastructure.persistence

import com.blackstore.domain.port.out.sales.SaleRecordStore
import com.blackstore.domain.model.StoreCoreOperationKind
import com.blackstore.domain.sales.SaleSaga
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.UUID
import javax.sql.DataSource
import com.blackstore.domain.port.out.sales.DurableSaleStore
import com.blackstore.domain.sales.*

@Component
@ConditionalOnProperty(name = ["blackstore.persistence.enabled"], havingValue = "true")
class JdbcSaleRecordStore(
    private val dataSource: DataSource,
) : SaleRecordStore, DurableSaleStore by JdbcDurableSaleRepository(dataSource) {
    private val writer = JdbcBlackStoreWriter()
    private val durable = JdbcDurableSaleRepository(dataSource)
    private val mapper = DurableCommandMapper()

    override fun recordIntentAndOutbox(saga: SaleSaga) {
        val clientId = UUID.fromString(saga.quadruple.clientInstanceId)
        val operationId = UUID.fromString(saga.quadruple.operationId)
        val command = saga.outbox.first { it.kind == StoreCoreOperationKind.RESERVE }
        val digest = command.openapiDigest.padEnd(64, '0').take(64)
        asRole("blackstore_app") { connection ->
            connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))").use { s -> s.setString(1,saga.quadruple.toString());s.execute() }
            val existing = connection.prepareStatement("SELECT id FROM sale_state_projection WHERE operation_id=?").use { s ->s.setObject(1,operationId);s.executeQuery().use { r ->if(r.next()) r.getLong(1) else null } }
            if(existing!=null) {
                val stored=durable.read(connection,existing)
                require(stored.saga.quadruple==saga.quadruple && stored.saga.cashSessionId==saga.cashSessionId && stored.saga.createdBy==saga.createdBy && stored.saga.lines==saga.lines)
                require(stored.saga.outbox.firstOrNull { it.kind==command.kind }?.let { mapper.encode(it)==mapper.encode(command) } == true) { "IDEMPOTENCY_PAYLOAD_MISMATCH" }
                return@asRole
            }
            authorize(connection,saga,saga.createdBy ?: error("trusted actor required"),null)
            val id =
                writer.insertPendingSale(
                    connection = connection,
                    clientInstanceId = clientId,
                    deviceId = saga.quadruple.deviceId,
                    saleId = saga.quadruple.saleId,
                    operationId = operationId,
                    cashSessionId = saga.cashSessionId,
                    createdBy = saga.createdBy ?: error("trusted actor is required for durable sale intent"),
                    contractVersion = command.contractVersion,
                    openapiDigest = digest,
                )
            insertCanonicalCommand(connection,saga,command,saga.createdBy!!,null)
            writer.insertAudit(connection, saga.createdBy, "INTENT_CREATED", "sale", id)
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
        admitTerminal(saga,StoreCoreOperationKind.COMMIT)
    }

    override fun recordCommitted(saga: SaleSaga) {
        asRole("blackstore_projection_worker") { connection ->
            val projectionId = findProjection(connection, saga)
            writer.advanceSaleStatus(connection, projectionId, "COMMIT_PENDING", "COMMITTED")
        }
    }

    override fun recordReleasePending(saga: SaleSaga) {
        admitTerminal(saga,StoreCoreOperationKind.RELEASE)
    }

    override fun recordReleased(saga: SaleSaga) {
        asRole("blackstore_projection_worker") { connection ->
            val projectionId = findProjection(connection, saga)
            writer.advanceSaleStatus(connection, projectionId, "RELEASE_PENDING", "RELEASED")
        }
    }

    private fun admitTerminal(saga: SaleSaga, kind: StoreCoreOperationKind) {
        val command = saga.outbox.first { it.kind == kind }
        val audit = saga.staffCommandAudit ?: error("trusted command actor is required")
        val expected = when(kind) {
            StoreCoreOperationKind.COMMIT -> com.blackstore.domain.sales.SaleStaffCommandEvent.COMMIT_REQUESTED
            StoreCoreOperationKind.RELEASE -> com.blackstore.domain.sales.SaleStaffCommandEvent.RELEASE_REQUESTED
            else -> error("unsupported staff command")
        }
        require(audit.event == expected)
        asRole("blackstore_app") { connection ->
            val stored=durable.lock(connection,saga.quadruple.operationId)
            authorize(connection,stored.saga,audit.actor.value,audit.reason)
            val ledger=ledger(connection,stored.saga)
            val decision=PaymentTransitionPolicy().terminal(stored.saga,ledger,kind)
            decision.assertAllowed()
            if(decision==TransitionDecision.TerminalReplay || decision==TransitionDecision.RecoverExistingCommand) {
                require(stored.saga.outbox.single { it.kind==kind }.let { mapper.encode(it)==mapper.encode(command) }) { "IDEMPOTENCY_PAYLOAD_MISMATCH" }
                return@asRole
            }
            require(command.reservationRef==stored.saga.evidence?.reservationRef)
            insertCanonicalCommand(connection,saga,command,audit.actor.value,audit.reason)
            val id=findProjection(connection,saga)
            if(kind==StoreCoreOperationKind.COMMIT) {
                if(stored.saga.status==SaleStatus.RESERVED) check(writer.advanceSaleStatus(connection,id,"RESERVED","PAYMENT_CAPTURED")==1)
                check(writer.advanceSaleStatus(connection,id,"PAYMENT_CAPTURED","COMMIT_PENDING")==1)
            } else check(writer.advanceSaleStatus(connection,id,stored.saga.status.name,"RELEASE_PENDING")==1)
            bumpVersion(connection,id,stored.version)
            writer.insertAudit(connection,audit.actor.value,audit.event.name,"sale",findProjection(connection,saga),audit.reason ?: "")
        }
    }

    internal fun insertCanonicalCommand(c: java.sql.Connection,saga: SaleSaga,command: OutboxCommand,actor: Long,reason: String?) {
        val normalized=if(command.payload==null && command.kind!=StoreCoreOperationKind.RESERVE) command.copy(payload=CanonicalCommandPayload.Terminal(requireNotNull(command.reservationRef))) else command
        val payload=mapper.encode(normalized)
        val id=c.prepareStatement("INSERT INTO storecore_outbox_commands(client_instance_id,device_id,sale_id,operation_id,operation_kind,canonical_path,contract_version,openapi_digest,request_hash,payload_redacted,canonical_payload,payload_hash,actor_id,cash_session_id,business_reason) VALUES(?,?,?,?,?,?,?,?,?,'{}',?::jsonb,?,?,?,?) RETURNING id").use { s ->
            s.setObject(1,UUID.fromString(command.quadruple.clientInstanceId));s.setString(2,command.quadruple.deviceId);s.setString(3,command.quadruple.saleId);s.setObject(4,UUID.fromString(command.quadruple.operationId));s.setString(5,command.kind.name);s.setString(6,command.canonicalPath);s.setString(7,command.contractVersion);s.setString(8,command.openapiDigest);s.setString(9,command.requestHash);s.setString(10,payload);s.setString(11,mapper.hash(payload));s.setLong(12,actor);s.setLong(13,saga.cashSessionId);s.setString(14,reason);s.executeQuery().use { r ->r.next();r.getLong(1) }
        }
        c.prepareStatement("INSERT INTO storecore_command_delivery(command_id,state) VALUES(?,'PENDING')").use { s ->s.setLong(1,id);s.executeUpdate() }
    }
    internal fun bumpVersion(c: java.sql.Connection,id: Long,version: Long) {
        c.prepareStatement("UPDATE sale_state_projection SET version=version+1,updated_at=now() WHERE id=? AND version=?").use { s ->s.setLong(1,id);s.setLong(2,version);check(s.executeUpdate()==1) }
    }
    internal fun authorize(c: java.sql.Connection,saga: SaleSaga,actor: Long,reason: String?) {
        c.prepareStatement("SELECT u.role_code,u.active,cs.cashier_id,cs.status FROM staff_users u CROSS JOIN cash_session_projection cs WHERE u.id=? AND cs.id=?").use { s ->
            s.setLong(1,actor);s.setLong(2,saga.cashSessionId);s.executeQuery().use { r ->
                require(r.next() && r.getBoolean(2) && com.blackstore.domain.cash.CashSessionStatus.fromWire(r.getString(4))==com.blackstore.domain.cash.CashSessionStatus.OPEN) { "staff or cash session unavailable" }
                val own=r.getLong(3)==actor
                val role=com.blackstore.domain.cash.StaffRole.fromWire(r.getString(1))
                require((role==com.blackstore.domain.cash.StaffRole.CASHIER && own) || (role in setOf(com.blackstore.domain.cash.StaffRole.SUPERVISOR,com.blackstore.domain.cash.StaffRole.OWNER) && (own || !reason.isNullOrBlank()))) { "sale ownership denied" }
            }
        }
    }
    internal fun ledger(c: java.sql.Connection,saga: SaleSaga): OperationLedger = c.prepareStatement("SELECT * FROM payments WHERE sale_id=? ORDER BY id").use { s ->
        s.setLong(1,findProjection(c,saga));s.executeQuery().use { r ->OperationLedger.Known(buildList { while(r.next()) add(PaymentLedgerEntry(saga.quadruple,r.getLong("id"),PaymentMethod.fromWire(r.getString("payment_method")),PaymentStatus.fromWire(r.getString("status")),r.getBigDecimal("amount"),r.getBigDecimal("fee_amount"))) }) }
    }

    private fun findProjection(connection: java.sql.Connection, saga: SaleSaga): Long =
        connection.prepareStatement(
            "SELECT id FROM sale_state_projection WHERE operation_id = ? AND client_instance_id = ? AND device_id = ? AND sale_id = ?",
        ).use { statement ->
            statement.setObject(1, UUID.fromString(saga.quadruple.operationId))
            statement.setObject(2, UUID.fromString(saga.quadruple.clientInstanceId))
            statement.setString(3, saga.quadruple.deviceId)
            statement.setString(4, saga.quadruple.saleId)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "sale ${saga.quadruple.operationId} is not persisted" }
                val id = rows.getLong(1)
                check(!rows.next()) { "sale identity is ambiguous" }
                id
            }
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
