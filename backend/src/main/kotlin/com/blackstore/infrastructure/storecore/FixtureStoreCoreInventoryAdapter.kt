package com.blackstore.infrastructure.storecore

import com.blackstore.application.storecore.StoreCoreEnvelope
import com.blackstore.application.storecore.StoreCoreEnvelopeValidator
import com.blackstore.domain.exception.ForbiddenOperationException
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.model.StoreCoreContractRef
import com.blackstore.domain.model.StoreCoreOperationKind
import com.blackstore.domain.model.StoreCoreOperationReceipt
import com.blackstore.domain.model.StoreCoreOperationState
import com.blackstore.domain.model.StoreCoreCanonicalContract
import com.blackstore.domain.port.out.storecore.CommitInventoryCommand
import com.blackstore.domain.port.out.storecore.OperationRetirementPort
import com.blackstore.domain.port.out.storecore.ReconcileProjection
import com.blackstore.domain.port.out.storecore.ReconcileQuery
import com.blackstore.domain.port.out.storecore.ReleaseInventoryCommand
import com.blackstore.domain.port.out.storecore.ReserveInventoryCommand
import com.blackstore.domain.port.out.storecore.StoreCoreInventoryPort
import com.blackstore.domain.port.out.storecore.StoreCoreReconcilePort
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Instant

@Component
@ConditionalOnProperty(
    name = ["blackstore.storecore.integration.mode"],
    havingValue = "fixture",
    matchIfMissing = true,
)
class FixtureStoreCoreInventoryAdapter(
    private val envelopeValidator: StoreCoreEnvelopeValidator,
    @Value("\${blackstore.storecore.contract.canonical-path}") private val canonicalPath: String,
    @Value("\${blackstore.storecore.contract.version}") private val contractVersion: String,
    @Value("\${blackstore.storecore.contract.sha256}") private val openapiDigest: String = StoreCoreCanonicalContract.SHA256,
) : StoreCoreInventoryPort, StoreCoreReconcilePort, OperationRetirementPort {

    val reserveAttempts: MutableList<String> = mutableListOf()
    var crashBeforeReceipt: Boolean = false
    private val receipts = linkedMapOf<String, StoreCoreOperationReceipt>()
    private val retired = mutableSetOf<String>()

    override fun reserve(command: ReserveInventoryCommand): StoreCoreOperationReceipt {
        val operationId = command.quadruple.operationId
        if (operationId in retired) {
            throw ForbiddenOperationException("OPERATION_RETIRED is not retryable and must not be re-posted")
        }
        reserveAttempts += operationId
        receipts[operationId]?.let { return it }
        if (crashBeforeReceipt) {
            crashBeforeReceipt = false
            throw IllegalStateException("fixture crash before receipt")
        }
        val receipt =
            receipt(
                command.quadruple,
                StoreCoreOperationKind.RESERVE,
                StoreCoreOperationState.RESERVED,
                command.lines.map { it.expectedPriceVersion }.distinct().ifEmpty { listOf("price-v1") },
            )
        val validated =
            envelopeValidator.requireSuccess(
                StoreCoreEnvelope(
                    code = 200,
                    data = receipt,
                    errorCode = null,
                    retryable = null,
                    message = null,
                    traceId = "fixture-${operationId}",
                ),
            )
        receipts[operationId] = validated
        return validated
    }

    val commitAttempts: MutableList<String> = mutableListOf()
    val releaseAttempts: MutableList<String> = mutableListOf()

    override fun commit(command: CommitInventoryCommand): StoreCoreOperationReceipt {
        rejectRetired(command.quadruple.operationId)
        commitAttempts += command.quadruple.operationId
        return accept(command.quadruple, StoreCoreOperationKind.COMMIT, StoreCoreOperationState.COMMITTED)
    }

    override fun release(command: ReleaseInventoryCommand): StoreCoreOperationReceipt {
        rejectRetired(command.quadruple.operationId)
        releaseAttempts += command.quadruple.operationId
        return accept(command.quadruple, StoreCoreOperationKind.RELEASE, StoreCoreOperationState.RELEASED)
    }

    override fun getOperation(quadruple: OperationQuadruple): StoreCoreOperationReceipt? = receipts[quadruple.operationId]

    override fun reconcile(query: ReconcileQuery): ReconcileProjection {
        val held = receipts.values.filter { it.receipt != null && it.receipt in query.knownReceipts }
        val unknown = query.knownReceipts.filter { receipt -> held.none { it.receipt == receipt } }
        return ReconcileProjection(present = held, unknownReceipts = unknown)
    }

    override fun isRetired(operationId: String): Boolean = operationId in retired

    override fun markRetired(operationId: String) {
        val errorCode =
            envelopeValidator.requireError(
                StoreCoreEnvelope<StoreCoreOperationReceipt>(
                    code = 410,
                    data = null,
                    errorCode = "OPERATION_RETIRED",
                    retryable = false,
                    message = "operation retired",
                    traceId = "fixture-retired",
                ),
            )
        check(errorCode == "OPERATION_RETIRED")
        retired += operationId
    }

    private fun rejectRetired(operationId: String) {
        if (operationId in retired) {
            throw ForbiddenOperationException("OPERATION_RETIRED is not retryable and must not be re-posted")
        }
    }

    private fun accept(
        quadruple: OperationQuadruple,
        kind: StoreCoreOperationKind,
        state: StoreCoreOperationState,
    ): StoreCoreOperationReceipt {
        val key = "${kind.name}:${quadruple.operationId}"
        receipts[key]?.let { return it }
        val validated =
            envelopeValidator.requireSuccess(
                StoreCoreEnvelope(
                    code = 200,
                    data =
                        receipt(
                            quadruple,
                            kind,
                            state,
                            receipts[quadruple.operationId]?.acceptedPriceVersions ?: listOf("price-v1"),
                        ),
                    errorCode = null,
                    retryable = null,
                    message = null,
                    traceId = "fixture-${quadruple.operationId}",
                ),
            )
        receipts[key] = validated
        return validated
    }

    private fun receipt(
        quadruple: OperationQuadruple,
        kind: StoreCoreOperationKind,
        state: StoreCoreOperationState,
        acceptedPriceVersions: List<String>,
    ) = StoreCoreOperationReceipt(
        quadruple = quadruple,
        kind = kind,
        state = state,
        reservationRef = "res-${quadruple.operationId}",
        receipt = "rcpt-${quadruple.operationId}",
        contract = StoreCoreContractRef(canonicalPath, contractVersion, openapiDigest),
        acceptedPriceVersions = acceptedPriceVersions,
        expiresAt = Instant.parse("2026-09-23T00:00:00Z"),
    )
}
