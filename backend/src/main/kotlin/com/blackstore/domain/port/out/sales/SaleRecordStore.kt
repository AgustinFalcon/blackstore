package com.blackstore.domain.port.out.sales

import com.blackstore.domain.sales.SaleSaga
import com.blackstore.domain.sales.SaleStatus
import java.time.Instant

interface SaleRecordStore {
    fun findRecorded(operationId: String): RecordedSale? = null

    fun recordIntentAndOutbox(saga: SaleSaga) {}

    fun recordInbox(
        saga: SaleSaga,
        responseHash: String,
        kind: String = "RESERVE",
        remoteState: String = "PENDING",
        receipt: String? = null,
        reservationRef: String? = null,
    ) {}

    fun recordReconciliationRequired(saga: SaleSaga) {}

    fun recordReserved(saga: SaleSaga)

    fun recordCommitPending(saga: SaleSaga)

    fun recordCommitted(saga: SaleSaga)

    fun recordReleasePending(saga: SaleSaga)

    fun recordReleased(saga: SaleSaga)
}

data class RecordedSale(
    val clientInstanceId: String,
    val deviceId: String,
    val saleId: String,
    val operationId: String,
    val cashSessionId: Long,
    val status: SaleStatus,
    val receipt: String?,
    val reservationRef: String?,
    val contractVersion: String?,
    val openapiDigest: String?,
    val acceptedPriceVersions: List<String> = emptyList(),
    val reconciliationReason: String? = null,
    val reservationExpiresAt: Instant? = null,
)
