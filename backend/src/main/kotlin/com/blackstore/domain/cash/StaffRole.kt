package com.blackstore.domain.cash

enum class StaffRole {
    CASHIER,
    SUPERVISOR,
    OWNER,
    AUDITOR,
    UNKNOWN;

    companion object {
        fun fromWire(raw: String?): StaffRole = entries.firstOrNull { it.name == raw } ?: UNKNOWN
    }
}
