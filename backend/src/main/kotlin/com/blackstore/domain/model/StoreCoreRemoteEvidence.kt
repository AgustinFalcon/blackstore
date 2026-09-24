package com.blackstore.domain.model

/**
 * Durable remote evidence BlackStore may persist before or after a StoreCore call.
 * Receipt and reservationRef stay null only for PENDING.
 */
data class StoreCoreRemoteEvidence(
    val quadruple: OperationQuadruple,
    val kind: StoreCoreOperationKind,
    val remoteState: StoreCoreOperationState?,
    val contract: StoreCoreContractRef,
    val receipt: String?,
    val reservationRef: String?,
    val acceptedPriceVersions: List<String>,
    val errorCode: String?,
    val retryable: Boolean?,
    val reconciliationReason: String?,
) {
    init {
        if (remoteState == StoreCoreOperationState.PENDING) {
            require(receipt == null && reservationRef == null) { "PENDING evidence must not invent receipt or reservationRef" }
        }
        if (remoteState == StoreCoreOperationState.EXPIRED) {
            require(!receipt.isNullOrBlank()) { "EXPIRED evidence requires receipt" }
            require(!reservationRef.isNullOrBlank()) { "EXPIRED evidence requires reservationRef" }
            require(acceptedPriceVersions.isNotEmpty()) { "EXPIRED evidence requires acceptedPriceVersions" }
            require(!contract.openapiDigestSha256.isNullOrBlank()) { "EXPIRED evidence requires openapiDigestSha256" }
            require(!reconciliationReason.isNullOrBlank()) { "EXPIRED evidence requires reconciliation_reason" }
        }
    }

    fun authorizesRepost(): Boolean = false
}
