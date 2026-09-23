package com.blackstore.infrastructure.sales

import com.blackstore.domain.port.out.sales.SaleRecordStore
import com.blackstore.domain.sales.SaleSaga
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    name = ["blackstore.persistence.enabled"],
    havingValue = "false",
    matchIfMissing = true,
)
class NoOpSaleRecordStore : SaleRecordStore {
    override fun recordReserved(saga: SaleSaga) = Unit

    override fun recordCommitPending(saga: SaleSaga) = Unit

    override fun recordCommitted(saga: SaleSaga) = Unit

    override fun recordReleasePending(saga: SaleSaga) = Unit

    override fun recordReleased(saga: SaleSaga) = Unit
}
