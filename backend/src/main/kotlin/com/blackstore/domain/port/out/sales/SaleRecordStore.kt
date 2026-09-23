package com.blackstore.domain.port.out.sales

import com.blackstore.domain.sales.SaleSaga

interface SaleRecordStore {
    fun recordReserved(saga: SaleSaga)

    fun recordCommitPending(saga: SaleSaga)

    fun recordCommitted(saga: SaleSaga)

    fun recordReleasePending(saga: SaleSaga)

    fun recordReleased(saga: SaleSaga)
}
