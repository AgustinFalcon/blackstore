package com.blackstore.infrastructure.persistence

import com.blackstore.domain.accounting.AccountingCommandDraft
import com.blackstore.domain.accounting.ExpenseInstruction
import com.blackstore.domain.cash.StaffRole
import com.blackstore.domain.identity.AuthenticatedStaff
import com.blackstore.domain.identity.StaffUserId
import com.blackstore.domain.port.out.accounting.AccountingMutationOutcome
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.model.StoreCoreContractRef
import com.blackstore.domain.model.StoreCoreOperationKind
import com.blackstore.domain.model.StoreCoreOperationReceipt
import com.blackstore.domain.model.StoreCoreOperationState
import com.blackstore.domain.sales.CanonicalCommandPayload
import com.blackstore.domain.sales.ClaimedSaleCommand
import com.blackstore.domain.sales.OutboxCommand
import com.blackstore.domain.sales.PaymentMethod
import com.blackstore.domain.sales.RemoteEvidence
import com.blackstore.domain.sales.AttemptOutcome
import org.postgresql.ds.PGSimpleDataSource
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/** Test-only process used to stop exactly one JVM at a controlled database commit boundary. */
object AccountingMutationCrashChild {
    @JvmStatic
    fun main(args: Array<String>) {
        val input = AccountingCrashInput.fromWire(args)
        val source = PGSimpleDataSource().apply {
            setURL(requireNotNull(System.getenv("BLACKSTORE_CRASH_JDBC_URL")))
            user = requireNotNull(System.getenv("BLACKSTORE_CRASH_JDBC_USER"))
            password = requireNotNull(System.getenv("BLACKSTORE_CRASH_JDBC_PASSWORD"))
            applicationName = input.applicationName
        }
        val proof = if (input.operation == AccountingCrashOperation.Recognition)
            AccountingCrashCommitProof.Recognition(input.identity().operationId)
        else AccountingCrashCommitProof.CommandReceipt(input.commandId)
        val barrierSource = CommitBoundaryDataSource(source, input.boundary, proof)
        val staff = AuthenticatedStaff(StaffUserId(input.staffId), "Crash harness cashier", StaffRole.CASHIER)
        if (input.operation == AccountingCrashOperation.Recognition) {
            applyRecognition(source, barrierSource, input)
            return
        }
        val commands = JdbcAccountingMutationCommands(barrierSource)
        val command = when (input.operation) {
            AccountingCrashOperation.Open -> AccountingCommandDraft.CashSessionOpen(
                input.commandId, 1, input.staffId, BigDecimal.ZERO, "crash harness opening")
            AccountingCrashOperation.Expense -> AccountingCommandDraft.ExpenseRecord(
                input.commandId, requireNotNull(input.cashSessionId), "crash harness expense",
                ExpenseInstruction.AccrueAndSettle("OPERATING", BigDecimal("1.00"), com.blackstore.domain.sales.PaymentMethod.CASH))
            AccountingCrashOperation.Close -> AccountingCommandDraft.CashSessionClose(
                input.commandId, requireNotNull(input.cashSessionId), BigDecimal.ZERO, "crash harness close")
            AccountingCrashOperation.PaymentCapture -> AccountingCommandDraft.PaymentCapture(
                input.commandId, input.identity(), PaymentMethod.CASH, BigDecimal("10.00"))
            AccountingCrashOperation.PaymentReverse -> AccountingCommandDraft.PaymentReverse(
                input.commandId, input.identity(), requireNotNull(System.getenv("BLACKSTORE_CRASH_PAYMENT_ID")).toLong(),
                "crash harness refund", "crash-harness-evidence")
            AccountingCrashOperation.Recognition -> error("recognition uses its worker transaction")
            AccountingCrashOperation.Unknown -> error("unknown crash operation")
        }
        check(commands.execute(staff, command) is AccountingMutationOutcome.Applied)
    }

    private fun applyRecognition(readSource: DataSource, mutationSource: DataSource, input: AccountingCrashInput) {
        val current = requireNotNull(JdbcDurableSaleRepository(readSource).findDurable(input.identity().operationId)).saga
        val evidence = requireNotNull(current.evidence)
        val command = OutboxCommand(current.quadruple, StoreCoreOperationKind.COMMIT,
            com.blackstore.domain.model.StoreCoreCanonicalContract.CANONICAL_PATH,
            evidence.contractVersion, evidence.openapiDigest, "c".repeat(64), evidence.reservationRef,
            CanonicalCommandPayload.Terminal(evidence.reservationRef))
        val claim = ClaimedSaleCommand(
            requireNotNull(System.getenv("BLACKSTORE_CRASH_CLAIM_ID")).toLong(), command,
            UUID.fromString(requireNotNull(System.getenv("BLACKSTORE_CRASH_CLAIM_TOKEN"))),
            requireNotNull(System.getenv("BLACKSTORE_CRASH_CLAIM_EPOCH")).toLong(), false,
            requireNotNull(System.getenv("BLACKSTORE_CRASH_CLAIM_ATTEMPTS")).toInt(),
            requireNotNull(System.getenv("BLACKSTORE_CRASH_CLAIM_ACTOR")).toLong(),
            requireNotNull(input.cashSessionId),
        )
        val receipt = StoreCoreOperationReceipt(current.quadruple, StoreCoreOperationKind.COMMIT,
            StoreCoreOperationState.COMMITTED, evidence.reservationRef, "commit-receipt-${current.quadruple.saleId}",
            StoreCoreContractRef(command.canonicalPath, command.contractVersion, command.openapiDigest),
            evidence.acceptedPriceVersions, null)
        val committed = current.applyRemoteDurable(StoreCoreOperationState.COMMITTED,
            RemoteEvidence(evidence.reservationRef, requireNotNull(receipt.receipt), evidence.contractVersion,
                evidence.openapiDigest, evidence.acceptedPriceVersions, null))
        check(JdbcDurableSaleRepository(mutationSource).applyClaimEvidence(
            claim, committed, "e".repeat(64), StoreCoreOperationState.COMMITTED.name, receipt) == AttemptOutcome.APPLIED)
    }
}

enum class AccountingCrashBoundary {
    BeforeCommit, AfterCommit, Unknown;
    companion object { fun fromWire(raw: String?): AccountingCrashBoundary = entries.firstOrNull { it.name == raw } ?: Unknown }
}

enum class AccountingCrashOperation {
    Open, Expense, Close, PaymentCapture, PaymentReverse, Recognition, Unknown;
    companion object { fun fromWire(raw: String?): AccountingCrashOperation = entries.firstOrNull { it.name == raw } ?: Unknown }
}

private data class AccountingCrashInput(
    val boundary: AccountingCrashBoundary,
    val operation: AccountingCrashOperation,
    val staffId: Long,
    val commandId: UUID,
    val cashSessionId: Long?,
    val applicationName: String,
) {
    fun identity() = OperationQuadruple(
        requireNotNull(System.getenv("BLACKSTORE_CRASH_CLIENT_ID")),
        requireNotNull(System.getenv("BLACKSTORE_CRASH_DEVICE_ID")),
        requireNotNull(System.getenv("BLACKSTORE_CRASH_SALE_ID")),
        requireNotNull(System.getenv("BLACKSTORE_CRASH_OPERATION_ID")),
    )

    companion object {
        fun fromWire(args: Array<String>): AccountingCrashInput {
            require(args.size == 6)
            val boundary = AccountingCrashBoundary.fromWire(args[0])
            val operation = AccountingCrashOperation.fromWire(args[1])
            require(boundary != AccountingCrashBoundary.Unknown && operation != AccountingCrashOperation.Unknown)
            val cash = args[4].toLong().takeIf { it > 0 }
            require(operation == AccountingCrashOperation.Open || cash != null)
            return AccountingCrashInput(boundary, operation, args[2].toLong(), UUID.fromString(args[3]), cash, args[5])
        }
    }
}

private class CommitBoundaryDataSource(
    private val delegate: DataSource,
    private val boundary: AccountingCrashBoundary,
    private val proof: AccountingCrashCommitProof,
) : DataSource by delegate {
    override fun getConnection(): Connection {
        val connection = delegate.connection
        return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, values ->
            if (method.name == "commit" && boundary == AccountingCrashBoundary.BeforeCommit) barrier()
            val result = try { method.invoke(connection, *(values ?: emptyArray())) }
            catch (error: InvocationTargetException) { throw error.targetException }
            if (method.name == "commit" && boundary == AccountingCrashBoundary.AfterCommit) {
                delegate.connection.use { verification ->
                    verification.createStatement().use { it.execute("SET ROLE blackstore_app") }
                    val count = when (proof) {
                        is AccountingCrashCommitProof.CommandReceipt -> verification.prepareStatement(
                            "SELECT count(*) FROM accounting_command_receipts WHERE command_id=?").use { statement ->
                            statement.setObject(1, proof.commandId); statement.executeQuery().use { rows -> check(rows.next()); rows.getInt(1) }
                        }
                        is AccountingCrashCommitProof.Recognition -> verification.prepareStatement(
                            "SELECT count(*) FROM commercial_recognitions r JOIN sale_state_projection s ON s.id=r.sale_id WHERE s.operation_id=?").use { statement ->
                            statement.setObject(1, UUID.fromString(proof.operationId)); statement.executeQuery().use { rows -> check(rows.next()); rows.getInt(1) }
                        }
                    }
                    check(count == 1)
                }
                barrier()
            }
            result
        } as Connection
    }

    private fun barrier() {
        println("CLR_CRASH_READY")
        System.out.flush()
        System.`in`.read()
    }
}

private sealed interface AccountingCrashCommitProof {
    data class CommandReceipt(val commandId: UUID) : AccountingCrashCommitProof
    data class Recognition(val operationId: String) : AccountingCrashCommitProof
}
