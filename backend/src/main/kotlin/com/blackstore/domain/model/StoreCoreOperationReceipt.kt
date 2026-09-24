package com.blackstore.domain.model

import java.time.Instant

/**
 * Domain view of a StoreCore operation receipt (from fixture or future adapter).
 * PENDING has no receipt/reservationRef. Durable states require both plus acceptedPriceVersions.
 */
data class StoreCoreOperationReceipt(
    val quadruple: OperationQuadruple,
    val kind: StoreCoreOperationKind,
    val state: StoreCoreOperationState,
    val reservationRef: String?,
    val receipt: String?,
    val contract: StoreCoreContractRef,
    val acceptedPriceVersions: List<String>,
    val expiresAt: Instant?,
) {
    init {
        when (state) {
            StoreCoreOperationState.PENDING -> {
                require(receipt == null) { "PENDING must not carry a receipt" }
                require(reservationRef == null) { "PENDING must not carry a reservationRef" }
            }
            StoreCoreOperationState.RESERVED,
            StoreCoreOperationState.COMMITTED,
            StoreCoreOperationState.RELEASED,
            StoreCoreOperationState.EXPIRED,
            -> {
                require(!receipt.isNullOrBlank()) { "$state requires receipt" }
                require(!reservationRef.isNullOrBlank()) { "$state requires reservationRef" }
                require(acceptedPriceVersions.isNotEmpty()) { "$state requires acceptedPriceVersions" }
                require(!contract.openapiDigestSha256.isNullOrBlank()) { "$state requires openapiDigestSha256" }
            }
        }
    }
}
