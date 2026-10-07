package com.blackstore.infrastructure.cash

import com.blackstore.domain.cash.CashAuditEvent
import com.blackstore.domain.cash.CashSession
import com.blackstore.domain.port.out.cash.CashSessionStore
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

@Component
@ConditionalOnProperty(
    name = ["blackstore.persistence.enabled"],
    havingValue = "false",
    matchIfMissing = true,
)
class InMemoryCashSessionStore : CashSessionStore {
    private val ids = AtomicLong(1)
    private val sessions = linkedMapOf<Long, CashSession>()
    private val audits = mutableListOf<CashAuditEvent>()

    override fun list(): List<CashSession> = synchronized(this) { sessions.values.toList() }

    internal fun save(session: CashSession): CashSession = synchronized(this) {
        val stored = if (session.id == 0L) session.copy(id = ids.getAndIncrement()) else session
        sessions[stored.id] = stored
        stored
    }

    internal fun appendClosureAudit(event: CashAuditEvent) = synchronized(this) {
        audits.add(event)
        Unit
    }
    internal fun <T> atomic(action: () -> T): T = synchronized(this) {
        val previousSessions=sessions.toMap()
        val previousAudits=audits.toList()
        try { action() } catch(error: Exception) {
            sessions.clear();sessions.putAll(previousSessions)
            audits.clear();audits.addAll(previousAudits)
            throw error
        }
    }
}
