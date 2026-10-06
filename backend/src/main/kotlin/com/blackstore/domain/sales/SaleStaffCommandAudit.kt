package com.blackstore.domain.sales

import com.blackstore.domain.identity.StaffUserId

enum class SaleStaffCommandEvent {
    COMMIT_REQUESTED, RELEASE_REQUESTED, UNKNOWN;
    companion object {
        fun fromWire(value: String?): SaleStaffCommandEvent = entries.firstOrNull { it != UNKNOWN && it.name == value } ?: UNKNOWN
    }
}
data class SaleStaffCommandAudit(val event: SaleStaffCommandEvent, val actor: StaffUserId, val reason: String?) {
    init { require(event != SaleStaffCommandEvent.UNKNOWN) }
}
