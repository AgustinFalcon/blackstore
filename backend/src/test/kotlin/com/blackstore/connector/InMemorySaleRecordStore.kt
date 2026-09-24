package com.blackstore.connector

import com.blackstore.domain.port.out.sales.SaleRecordStore
import com.blackstore.domain.sales.SaleSaga

class InMemorySaleRecordStore : SaleRecordStore {
    val events = mutableListOf<String>()

    override fun recordIntentAndOutbox(saga: SaleSaga) {
        events += "INTENT_OUTBOX"
    }

    override fun recordInbox(
        saga: SaleSaga,
        responseHash: String,
        kind: String,
        remoteState: String,
        receipt: String?,
        reservationRef: String?,
    ) {
        events += "INBOX:$kind:$remoteState"
    }

    override fun recordReconciliationRequired(saga: SaleSaga) {
        events += "RECONCILIATION"
    }

    override fun recordReserved(saga: SaleSaga) {
        events += "RESERVED"
    }

    override fun recordCommitPending(saga: SaleSaga) {
        events += "COMMIT_PENDING"
    }

    override fun recordCommitted(saga: SaleSaga) {
        events += "COMMITTED"
    }

    override fun recordReleasePending(saga: SaleSaga) {
        events += "RELEASE_PENDING"
    }

    override fun recordReleased(saga: SaleSaga) {
        events += "RELEASED"
    }
}
