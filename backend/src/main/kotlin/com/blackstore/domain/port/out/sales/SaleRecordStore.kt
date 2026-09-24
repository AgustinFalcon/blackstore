package com.blackstore.domain.port.out.sales

import com.blackstore.domain.sales.SaleSaga

interface SaleRecordStore {
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
