package com.blackstore.application.dto.storecore

import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.model.StoreCoreContractRef
import com.blackstore.domain.model.StoreCoreOperationKind
import com.blackstore.domain.model.StoreCoreOperationReceipt
import com.blackstore.domain.model.StoreCoreOperationState
import java.time.Instant

/**
 * Wire-shaped receipt. Infrastructure Jackson may bind this; domain never sees it.
 */
data class StoreCoreReceiptDto(
    val state: String,
    val contractVersion: String,
    val receipt: String? = null,
    val reservationRef: String? = null,
    val acceptedPriceVersions: List<String> = emptyList(),
    val expiresAt: Instant? = null,
)

fun StoreCoreReceiptDto.toDomain(
    quadruple: OperationQuadruple,
    kind: StoreCoreOperationKind,
    contractPath: String,
    digest: String?,
): StoreCoreOperationReceipt {
    val state = StoreCoreOperationState.valueOf(state)
    return StoreCoreOperationReceipt(
        quadruple = quadruple,
        kind = kind,
        state = state,
        reservationRef = reservationRef,
        receipt = receipt,
        contract = StoreCoreContractRef(contractPath, contractVersion, digest),
        acceptedPriceVersions = acceptedPriceVersions,
        expiresAt = expiresAt,
    )
}
