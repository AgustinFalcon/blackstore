package com.blackstore.infrastructure.persistence

import com.blackstore.application.sales.SaleCommandFingerprint
import com.blackstore.application.sales.ReserveRequestFingerprint
import com.blackstore.application.sales.SaleReceiptAuthorityPolicy
import com.blackstore.application.sales.SaleReceiptReplayPolicy
import com.blackstore.application.sales.SaleLifecycleAdmissionStep
import com.blackstore.application.storecore.StoreCoreRequestHash
import com.blackstore.domain.accounting.*
import com.blackstore.domain.cash.*
import com.blackstore.domain.catalog.CatalogSalePolicy
import com.blackstore.domain.companion.CompanionEnvironment
import com.blackstore.domain.compliance.FiscalBoundaryPolicy
import com.blackstore.domain.identity.*
import com.blackstore.domain.model.*
import com.blackstore.domain.port.out.sales.SaleMutationCommands
import com.blackstore.domain.port.out.storecore.*
import com.blackstore.domain.sales.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

/** Admission never performs HTTP. Its receipt is returned only after the physical commit. */
@Component
@ConditionalOnProperty(name=["blackstore.persistence.enabled"],havingValue="true")
class JdbcSaleMutationCommands(private val source: DataSource, private val catalog: StoreCoreCatalogPort,
    @Value("\${blackstore.storecore.contract.canonical-path}") private val path: String,
    @Value("\${blackstore.storecore.contract.version}") private val version: String,
    @Value("\${blackstore.storecore.contract.sha256}") private val digest: String,
    @Value("\${blackstore.companion.stored.environment:TEST}") private val environment: String = "TEST",
    @Value("\${blackstore.pos.terminal-id:0}") terminalId: Long = 0,
    @Value("\${blackstore.storecore.transport.client-instance-id:}") connectorClientInstanceId: String = "") : SaleMutationCommands {
    private val contextReader = JdbcPosContextReader(terminalId,connectorClientInstanceId)
    private val contextValidation = com.blackstore.application.pos.PosContextValidationStep()
    private val lifecycle = JdbcAccountingLifecycleAdmission()
    private val locks = JdbcAccountingAggregateLocks()
    private val durable = JdbcDurableSaleRepository(source)
    private val records = JdbcSaleRecordStore(source)
    private val writer = JdbcBlackStoreWriter()
    private val fingerprints = SaleCommandFingerprint()
    private val policy = StaffAuthorizationPolicy()
    private val serialization = SerializationStep()
    private val authority = AuthorityStep()
    private val replay = ReceiptReplayStep()
    private val aggregate = AggregateStep()
    private val admission = LifecycleAdmissionStep()
    private val posContext = PosContextAdmissionStep()
    private val persistence = PersistAdmissionStep()

    override fun execute(staff: AuthenticatedStaff, command: SaleCommand): SaleCommandResult = safely(staff) {
        transaction(false) { c ->
            lifecycle.lock(c)
            serialization.acquire(c,command)
            val saved = receipt(c,command.commandId)
            val scope=aggregate.lock(c,command,saved)
            val actor=authority.current(c,staff,command,scope.cash)
            replay.resolve(command,saved,actor,scope.cash)?.let { return@transaction it }
            if(command is SaleCommand.Reserve) {
                if(projection(c,command.identity.operationId)!=null) {
                    // Existing operations require full identity visibility before conflict disclosure.
                    // No terminal lock/binding is needed because this path cannot admit an intention.
                    aggregate.verify(c,command,scope.cash)
                    reject(SaleCommandFailure.ExistingOperationCommand)
                }
                admission.requireNew(c,scope.cash)
                posContext.require(c,command,scope.cash)
            }
            val stored=aggregate.verify(c,command,scope.cash)
            if(command !is SaleCommand.Reserve) admission.requireNew(c,scope.cash)
            persistence.write(c,actor,command,scope.cash.id,stored)
        }
    }

    private inner class SerializationStep {
        fun acquire(c: Connection,command: SaleCommand) {
            serialize(c,"sale-command:${command.commandId}")
            serialize(c,"sale-operation:${command.identity.operationId}")
        }
    }
    private data class LockedScope(val cash: OwnedCashSession)
    private inner class AggregateStep {
        fun lock(c: Connection,command: SaleCommand,saved: SaleCommandAdmissionReceipt?): LockedScope {
            val cashId = saved?.cashSessionId ?: locateOperationCash(c,command.identity.operationId) ?: (command as? SaleCommand.Reserve)?.cashSessionId
                ?: reject(SaleCommandFailure.NotVisible)
            // Locate is only a hint; current authority and identity are checked after waiting.
            cash(c,cashId) ?: reject(SaleCommandFailure.NotVisible)
            locks.lockCash(c,cashId)
            val currentCash = cash(c,cashId) ?: reject(SaleCommandFailure.NotVisible)
            return LockedScope(currentCash)
        }
        fun verify(c: Connection,command: SaleCommand,cash: OwnedCashSession): StoredSale? {
            val cashId=cash.id
            if(command is SaleCommand.Reserve && command.cashSessionId!=cashId) reject(SaleCommandFailure.NotVisible)
            val projection = projection(c,command.identity.operationId)
            val stored = projection?.let { durable.lock(c,command.identity.operationId) }
            if(stored!=null && (stored.saga.quadruple!=command.identity || stored.saga.cashSessionId!=cashId)) reject(SaleCommandFailure.NotVisible)
            if(command !is SaleCommand.Reserve && stored==null) reject(SaleCommandFailure.NotVisible)
            if(command is SaleCommand.Reserve && stored!=null) reject(SaleCommandFailure.ExistingOperationCommand)
            if(stored?.saga?.outbox?.any { it.kind==remoteKind(command.kind) }==true) reject(SaleCommandFailure.ExistingOperationCommand)
            return stored
        }
    }
    private inner class AuthorityStep {
        fun current(c: Connection,staff: AuthenticatedStaff,command: SaleCommand,cash: OwnedCashSession): AuthenticatedStaff = actor(c,staff.id.value).also {
            authorize(it,command.kind,cash,command.reason,true)
        }
    }
    private inner class ReceiptReplayStep {
        fun resolve(command: SaleCommand,saved: SaleCommandAdmissionReceipt?,actor: AuthenticatedStaff,cash: OwnedCashSession): SaleCommandResult.Accepted? {
            saved ?: return null
            authorize(actor,saved.kind,cash,command.reason,true)
            SaleReceiptReplayPolicy().failure(command,saved,fingerprints.hash(saved.actorId,saved.cashSessionId,command))?.let(::reject)
            return SaleCommandResult.Accepted(saved,true)
        }
    }
    private inner class LifecycleAdmissionStep {
        fun requireNew(c: Connection,cash: OwnedCashSession) { SaleLifecycleAdmissionStep().failure(lifecycle.state(c),cash.open)?.let(::reject) }
    }
    private inner class PosContextAdmissionStep {
        fun require(c: Connection,command: SaleCommand.Reserve,cash: OwnedCashSession) {
            // Global order: fence -> command/operation -> cash -> terminal -> sale -> delivery.
            val cashTerminal = scalar(c,"SELECT terminal_id FROM cash_session_projection WHERE id=?",cash.id) { it.getLong(1) }
                ?: reject(SaleCommandFailure.NotVisible)
            contextValidation.failure(contextReader.read(c,true),command.identity,cashTerminal)?.let(::reject)
        }
    }
    private inner class PersistAdmissionStep {
        fun write(c: Connection,actor: AuthenticatedStaff,command: SaleCommand,cashId: Long,stored: StoredSale?): SaleCommandResult.Accepted {
            val saga = when(command) {
                is SaleCommand.Reserve -> {
                    reserve(c,actor,command)
                }
                is SaleCommand.Commit, is SaleCommand.Release -> {
                    terminal(c,actor,command,requireNotNull(stored))
                }
            }
            val outbox = scalar(c,"SELECT id FROM storecore_outbox_commands WHERE operation_id=? AND operation_kind=?",UUID.fromString(command.identity.operationId),remoteKind(command.kind).name) { it.getLong(1) }
                ?: reject(SaleCommandFailure.Unavailable)
            val intent = scalar(c,"SELECT sale_intent_id FROM sale_state_projection WHERE operation_id=?",UUID.fromString(command.identity.operationId)) { it.getLong(1) }
                ?: reject(SaleCommandFailure.Unavailable)
            val hash = fingerprints.hash(actor.id.value,cashId,command)
            c.prepareStatement("INSERT INTO sale_command_admission_receipts(command_id,kind,payload_hash,actor_id,cash_session_id,client_instance_id,device_id,sale_id,operation_id,intent_id,outbox_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)").use { s ->
                listOf(command.commandId,command.kind.name,hash,actor.id.value,cashId,UUID.fromString(saga.quadruple.clientInstanceId),saga.quadruple.deviceId,saga.quadruple.saleId,UUID.fromString(saga.quadruple.operationId),intent,outbox).forEachIndexed { i,v -> s.setObject(i+1,v) };s.executeUpdate()
            }
            return SaleCommandResult.Accepted(requireNotNull(receipt(c,command.commandId)))
        }
    }

    override fun findReceipt(staff: AuthenticatedStaff, commandId: UUID): SaleCommandResult = safely(staff) {
        transaction(true) { c ->
            val actor = actor(c,staff.id.value)
            if(!policy.permits(actor.role,StaffPermission.SaleCommandRead)) reject(SaleCommandFailure.Forbidden)
            val saved = receipt(c,commandId) ?: return@transaction SaleCommandResult.NotFound
            val owned = cash(c,saved.cashSessionId) ?: return@transaction SaleCommandResult.NotFound
            if(actor.role==StaffRole.CASHIER && owned.cashierId!=actor.id) return@transaction SaleCommandResult.NotFound
            authorize(actor,saved.kind,owned,null,false)
            SaleCommandResult.Accepted(saved,true)
        }
    }

    private fun reserve(c: Connection, actor: AuthenticatedStaff, command: SaleCommand.Reserve): SaleSaga {
        val now = scalar(c,"SELECT clock_timestamp()") { it.getTimestamp(1).toInstant() }!!
        val snapshot = catalog.currentSnapshot() ?: reject(SaleCommandFailure.Unavailable)
        CatalogSalePolicy().assertSaleAllowed(snapshot,now)
        val item = snapshot.items.singleOrNull { it.variantId == command.variantId && it.sku == command.line.sku }
            ?: reject(SaleCommandFailure.Validation)
        // Pricing authority is the catalog, never a browser assertion.
        if(item.unitPrice == null || MoneyPolicy.normalize(item.unitPrice) != command.line.originalUnitPrice || item.priceVersion != command.expectedPriceVersion || command.line.discountAmount.signum()!=0)
            reject(SaleCommandFailure.Validation)
        val q = command.identity
        val lines = listOf(ReserveLineCommand(command.variantId,command.quantity,command.expectedPriceVersion))
        val remote = OutboxCommand(q,StoreCoreOperationKind.RESERVE,path,version,digest,
            ReserveRequestFingerprint.hash(q,command.cashSessionId,lines,listOf(command.line),command.reason),
            payload=CanonicalCommandPayload.Reserve(snapshot.version,listOf(CanonicalReserveLine(command.variantId,command.quantity,command.expectedPriceVersion))))
        val saga = SaleSaga(q,command.cashSessionId,lines=listOf(command.line),createdBy=actor.id.value).withReserveCommand(remote)
        val id = writer.insertPendingSale(c,UUID.fromString(q.clientInstanceId),q.deviceId,q.saleId,UUID.fromString(q.operationId),command.cashSessionId,actor.id.value,version,digest)
        records.insertCanonicalCommand(c,saga,remote,actor.id.value,command.reason)
        writer.insertSaleLine(c,id,command.line.sku,command.line.productName,command.quantity,command.line.originalUnitPrice,command.line.discountAmount)
        writer.insertAudit(c,actor.id.value,SaleStaffCommandEvent.RESERVE_REQUESTED.name,"sale",id,command.reason.orEmpty())
        return saga
    }

    private fun terminal(c: Connection, actor: AuthenticatedStaff, command: SaleCommand, stored: StoredSale): SaleSaga {
        val saga=stored.saga
        val kind=remoteKind(command.kind)
        if(saga.outbox.any { it.kind==kind }) reject(SaleCommandFailure.ExistingOperationCommand)
        // Legacy reserve identities are not adopted by creating v2 receipts around them.
        if(scalar(c,"SELECT command_id FROM sale_command_admission_receipts WHERE operation_id=? AND kind='Reserve'",UUID.fromString(command.identity.operationId)) { it.getObject(1) } == null)
            reject(SaleCommandFailure.ExistingOperationCommand)
        if(PaymentTransitionPolicy(PaymentLedgerSemantics.NetCapturedAndRefunded).terminal(saga,records.ledger(c,saga),kind)!=TransitionDecision.NewCommand)
            reject(SaleCommandFailure.TransitionConflict)
        if(kind==StoreCoreOperationKind.COMMIT) FiscalBoundaryPolicy().assertCanCommit(CompanionEnvironment.valueOf(environment),saga.fiscalStatus,null,
            scalar(c,"SELECT clock_timestamp()") { it.getTimestamp(1).toInstant() }!!)
        val evidence=saga.evidence ?: reject(SaleCommandFailure.TransitionConflict)
        if(evidence.contractVersion!=version || evidence.openapiDigest!=digest) reject(SaleCommandFailure.TransitionConflict)
        val remote=OutboxCommand(saga.quadruple,kind,path,version,digest,StoreCoreRequestHash.hex(kind,saga.quadruple.operationId),
            reservationRef=evidence.reservationRef,payload=CanonicalCommandPayload.Terminal(evidence.reservationRef))
        records.insertCanonicalCommand(c,saga,remote,actor.id.value,command.reason)
        c.createStatement().use { it.execute("SET LOCAL ROLE blackstore_projection_worker") }
        if(kind==StoreCoreOperationKind.COMMIT) {
            if(saga.status==SaleStatus.RESERVED) check(writer.advanceSaleStatus(c,stored.projectionId,"RESERVED","PAYMENT_CAPTURED")==1)
            check(writer.advanceSaleStatus(c,stored.projectionId,"PAYMENT_CAPTURED","COMMIT_PENDING")==1)
        } else check(writer.advanceSaleStatus(c,stored.projectionId,saga.status.name,"RELEASE_PENDING")==1)
        records.bumpVersion(c,stored.projectionId,stored.version)
        c.createStatement().use { it.execute("SET LOCAL ROLE blackstore_app") }
        writer.insertAudit(c,actor.id.value,if(kind==StoreCoreOperationKind.COMMIT) SaleStaffCommandEvent.COMMIT_REQUESTED.name else SaleStaffCommandEvent.RELEASE_REQUESTED.name,"sale",stored.projectionId,command.reason.orEmpty())
        return saga
    }

    private fun remoteKind(kind: SaleCommandKind)=when(kind) { SaleCommandKind.Reserve -> StoreCoreOperationKind.RESERVE; SaleCommandKind.Commit -> StoreCoreOperationKind.COMMIT; SaleCommandKind.Release -> StoreCoreOperationKind.RELEASE; SaleCommandKind.Unknown -> reject(SaleCommandFailure.Validation) }
    private fun authorize(actor: AuthenticatedStaff,kind: SaleCommandKind,cash: OwnedCashSession,reason: String?,mutation: Boolean) {
        SaleReceiptAuthorityPolicy().failure(actor,kind,cash,reason,mutation)?.let(::reject)
    }
    private fun actor(c: Connection,id: Long): AuthenticatedStaff = scalar(c,"SELECT display_name,role_code FROM staff_users WHERE id=? AND active",id) {
        AuthenticatedStaff(StaffUserId(id),it.getString(1),StaffRole.fromWire(it.getString(2)))
    } ?: reject(SaleCommandFailure.Forbidden)
    private fun cash(c: Connection,id: Long): OwnedCashSession? = scalar(c,"SELECT cashier_id,status FROM cash_session_projection WHERE id=?",id) {
        OwnedCashSession(id,StaffUserId(it.getLong(1)),CashSessionStatus.fromWire(it.getString(2)))
    }
    private fun locateOperationCash(c: Connection,id: String): Long? = scalar(c,"SELECT cash_session_id FROM sale_intents WHERE operation_id=?",UUID.fromString(id)) { it.getLong(1) }
    private fun projection(c: Connection,id: String): Long? = scalar(c,"SELECT id FROM sale_state_projection WHERE operation_id=?",UUID.fromString(id)) { it.getLong(1) }
    private fun receipt(c: Connection,id: UUID): SaleCommandAdmissionReceipt? = scalar(c,"SELECT * FROM sale_command_admission_receipts WHERE command_id=?",id) {
        SaleCommandAdmissionReceipt(id,SaleCommandKind.fromWire(it.getString("kind")),it.getString("payload_hash"),it.getLong("actor_id"),it.getLong("cash_session_id"),
            OperationQuadruple(it.getString("client_instance_id"),it.getString("device_id"),it.getString("sale_id"),it.getString("operation_id")),it.getLong("intent_id"),it.getLong("outbox_id"),it.getTimestamp("accepted_at").toInstant())
    }
    private fun serialize(c: Connection,key: String) { c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))").use { it.setString(1,key);it.execute() } }
    private fun <T> scalar(c: Connection,sql: String,vararg values: Any?,decode: (ResultSet)->T): T? = c.prepareStatement(sql).use { s -> values.forEachIndexed { i,v -> s.setObject(i+1,v) };s.executeQuery().use { if(it.next()) decode(it) else null } }
    private fun <T> transaction(readOnly: Boolean,block: (Connection)->T): T=source.connection.use { c ->
        c.autoCommit=false
        try { c.isReadOnly=readOnly; if(readOnly) c.transactionIsolation=Connection.TRANSACTION_REPEATABLE_READ
            c.createStatement().use { it.execute("SET LOCAL ROLE blackstore_app") };val result=block(c);c.commit();result
        } catch(e: Exception) { c.rollback();throw e }
    }
    private class Denied(val failure: SaleCommandFailure): RuntimeException()
    private fun reject(failure: SaleCommandFailure): Nothing=throw Denied(failure)
    private fun safely(staff: AuthenticatedStaff,block: ()->SaleCommandResult): SaleCommandResult=try { block() }
        catch(e: Denied) { denial(staff,e.failure);SaleCommandResult.Rejected(e.failure) }
        catch(e: AccountingAdmissionException) { SaleCommandResult.Rejected(when(e.failure) {
            AccountingCommandFailure.NotActivated -> SaleCommandFailure.NotActivated; AccountingCommandFailure.Paused -> SaleCommandFailure.Paused; else -> SaleCommandFailure.Unavailable }) }
        catch(_: SQLException) { SaleCommandResult.Unavailable }
        catch(_: com.blackstore.domain.exception.ForbiddenOperationException) { SaleCommandResult.Rejected(SaleCommandFailure.TransitionConflict) }
        catch(_: IllegalArgumentException) { SaleCommandResult.Rejected(SaleCommandFailure.Validation) }
    private fun denial(staff: AuthenticatedStaff,failure: SaleCommandFailure) {
        if(failure !in setOf(SaleCommandFailure.Forbidden,SaleCommandFailure.NotVisible)) return
        runCatching { transaction(false) { c -> writer.insertAudit(c,staff.id.value,"AUTHORIZATION_DENIED","staff_user",staff.id.value,failure.wire) } }
    }
}
