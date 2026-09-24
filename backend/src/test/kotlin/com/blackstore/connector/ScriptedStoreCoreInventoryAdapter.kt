package com.blackstore.connector

import com.blackstore.domain.exception.StoreCoreRemoteFault
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.model.StoreCoreCanonicalContract
import com.blackstore.domain.model.StoreCoreContractRef
import com.blackstore.domain.model.StoreCoreOperationKind
import com.blackstore.domain.model.StoreCoreOperationReceipt
import com.blackstore.domain.model.StoreCoreOperationState
import com.blackstore.domain.port.out.storecore.CommitInventoryCommand
import com.blackstore.domain.port.out.storecore.OperationRetirementPort
import com.blackstore.domain.port.out.storecore.ReconcileProjection
import com.blackstore.domain.port.out.storecore.ReconcileQuery
import com.blackstore.domain.port.out.storecore.ReleaseInventoryCommand
import com.blackstore.domain.port.out.storecore.ReserveInventoryCommand
import com.blackstore.domain.port.out.storecore.StoreCoreInventoryPort
import com.blackstore.domain.port.out.storecore.StoreCoreReconcilePort
import java.time.Instant

sealed class ReserveScript {
    data class Receipt(val receipt: StoreCoreOperationReceipt) : ReserveScript()

    data class Fault(val errorCode: String, val retryable: Boolean = false) : ReserveScript()

    data class Crash(val message: String = "scripted crash") : ReserveScript()
}

class ScriptedStoreCoreInventoryAdapter(
    var reserveScript: ReserveScript,
    var getReceipt: StoreCoreOperationReceipt? = null,
    var commitScript: ReserveScript? = null,
    var releaseScript: ReserveScript? = null,
    val reserveScripts: MutableList<ReserveScript> = mutableListOf(),
    val commitScripts: MutableList<ReserveScript> = mutableListOf(),
    val releaseScripts: MutableList<ReserveScript> = mutableListOf(),
) : StoreCoreInventoryPort, StoreCoreReconcilePort, OperationRetirementPort {

    val reserveAttempts = mutableListOf<String>()
    val commitAttempts = mutableListOf<String>()
    val releaseAttempts = mutableListOf<String>()
    val getAttempts = mutableListOf<String>()
    val commitReservationRefs = mutableListOf<String>()
    val releaseReservationRefs = mutableListOf<String>()
    private val retired = mutableSetOf<String>()

    override fun reserve(command: ReserveInventoryCommand): StoreCoreOperationReceipt {
        reserveAttempts += command.quadruple.operationId
        if (command.quadruple.operationId in retired) {
            throw StoreCoreRemoteFault("OPERATION_RETIRED")
        }
        return when (val script = reserveScripts.removeFirstOrNull() ?: reserveScript) {
            is ReserveScript.Receipt -> script.receipt
            is ReserveScript.Fault -> throw StoreCoreRemoteFault(script.errorCode, script.retryable)
            is ReserveScript.Crash -> throw IllegalStateException(script.message)
        }
    }

    override fun commit(command: CommitInventoryCommand): StoreCoreOperationReceipt {
        commitAttempts += command.quadruple.operationId
        commitReservationRefs += command.reservationRef
        val script = commitScripts.removeFirstOrNull() ?: commitScript
        commitScript = null
        return play(command.quadruple, script, StoreCoreOperationKind.COMMIT, StoreCoreOperationState.COMMITTED)
    }

    override fun release(command: ReleaseInventoryCommand): StoreCoreOperationReceipt {
        releaseAttempts += command.quadruple.operationId
        releaseReservationRefs += command.reservationRef
        val script = releaseScripts.removeFirstOrNull() ?: releaseScript
        releaseScript = null
        return play(command.quadruple, script, StoreCoreOperationKind.RELEASE, StoreCoreOperationState.RELEASED)
    }

    private fun play(
        quadruple: OperationQuadruple,
        script: ReserveScript?,
        kind: StoreCoreOperationKind,
        success: StoreCoreOperationState,
    ): StoreCoreOperationReceipt =
        when (script) {
            is ReserveScript.Receipt -> script.receipt
            is ReserveScript.Fault -> throw StoreCoreRemoteFault(script.errorCode, script.retryable)
            is ReserveScript.Crash -> throw IllegalStateException(script.message)
            null -> durable(quadruple, kind, success)
        }

    override fun getOperation(quadruple: OperationQuadruple): StoreCoreOperationReceipt? {
        getAttempts += quadruple.operationId
        return getReceipt
    }

    override fun reconcile(query: ReconcileQuery): ReconcileProjection =
        ReconcileProjection(present = emptyList(), unknownReceipts = query.knownReceipts)

    override fun isRetired(operationId: String): Boolean = operationId in retired

    override fun markRetired(operationId: String) {
        retired += operationId
    }

    companion object {
        fun durable(
            quadruple: OperationQuadruple,
            kind: StoreCoreOperationKind = StoreCoreOperationKind.RESERVE,
            state: StoreCoreOperationState = StoreCoreOperationState.RESERVED,
            reservationRef: String? = null,
            acceptedPriceVersions: List<String> = listOf("price-v1"),
        ) = StoreCoreOperationReceipt(
            quadruple = quadruple,
            kind = kind,
            state = state,
            reservationRef = if (state == StoreCoreOperationState.PENDING) null else reservationRef ?: "res-${quadruple.operationId}",
            receipt = if (state == StoreCoreOperationState.PENDING) null else "rcpt-${quadruple.operationId}",
            contract =
                StoreCoreContractRef(
                    StoreCoreCanonicalContract.CANONICAL_PATH,
                    StoreCoreCanonicalContract.VERSION,
                    if (state == StoreCoreOperationState.PENDING) null else StoreCoreCanonicalContract.SHA256,
                ),
            acceptedPriceVersions = if (state == StoreCoreOperationState.PENDING) emptyList() else acceptedPriceVersions,
            expiresAt = Instant.parse("2026-09-23T00:00:00Z"),
        )
    }
}
