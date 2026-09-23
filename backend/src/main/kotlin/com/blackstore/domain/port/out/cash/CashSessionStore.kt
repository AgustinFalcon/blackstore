package com.blackstore.domain.port.out.cash

import com.blackstore.domain.cash.CashAuditEvent
import com.blackstore.domain.cash.CashSession

interface CashSessionStore {
    fun list(): List<CashSession>

    fun save(session: CashSession): CashSession

    fun appendClosureAudit(event: CashAuditEvent)
}
