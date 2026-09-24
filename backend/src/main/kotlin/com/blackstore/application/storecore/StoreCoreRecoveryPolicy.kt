package com.blackstore.application.storecore

import com.blackstore.domain.model.StoreCoreOperationReceipt
import com.blackstore.domain.model.StoreCoreOperationState
import com.blackstore.domain.model.StoreCoreRecoveryAction
import com.blackstore.domain.model.StoreCoreRemoteEvidence

/**
 * Maps a contract-neutral remote outcome to the next recovery action.
 * Branches on state and errorCode, never on a message or HTTP type.
 */
object StoreCoreRecoveryPolicy {
    fun actionFor(receipt: StoreCoreOperationReceipt): StoreCoreRecoveryAction =
        when (receipt.state) {
            StoreCoreOperationState.PENDING -> StoreCoreRecoveryAction.RETAIN_PENDING
            StoreCoreOperationState.RESERVED,
            StoreCoreOperationState.COMMITTED,
            StoreCoreOperationState.RELEASED,
            -> StoreCoreRecoveryAction.RETAIN_DURABLE_EVIDENCE
            StoreCoreOperationState.EXPIRED -> StoreCoreRecoveryAction.RECORD_RECONCILIATION_REQUIRED
        }

    fun actionForError(errorCode: String): StoreCoreRecoveryAction =
        when (errorCode) {
            "OPERATION_RETIRED" -> StoreCoreRecoveryAction.NEVER_REPOST
            "CONFLICT" -> StoreCoreRecoveryAction.GET_SAME_QUADRUPLE
            "INSUFFICIENT_STOCK",
            "CATALOG_VERSION_STALE",
            "VALIDATION",
            "NOT_FOUND",
            -> StoreCoreRecoveryAction.NEW_OPERATION_SAME_SALE
            "IDEMPOTENCY_PAYLOAD_MISMATCH" -> StoreCoreRecoveryAction.RETAIN_DURABLE_EVIDENCE
            else -> StoreCoreRecoveryAction.NEVER_REPOST
        }

    fun evidenceFrom(receipt: StoreCoreOperationReceipt): StoreCoreRemoteEvidence {
        val reason =
            if (receipt.state == StoreCoreOperationState.EXPIRED) {
                "EXPIRED remote tuple requires reconciliation"
            } else {
                null
            }
        return StoreCoreRemoteEvidence(
            quadruple = receipt.quadruple,
            kind = receipt.kind,
            remoteState = receipt.state,
            contract = receipt.contract,
            receipt = receipt.receipt,
            reservationRef = receipt.reservationRef,
            acceptedPriceVersions = receipt.acceptedPriceVersions,
            errorCode = null,
            retryable = null,
            reconciliationReason = reason,
        )
    }
}
