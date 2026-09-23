package com.blackstore.domain.model

import java.time.Instant

/**
 * Domain view of a StoreCore operation receipt (from fixture or future adapter).
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
)
