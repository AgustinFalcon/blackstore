package com.blackstore.application.cash

import com.blackstore.domain.cash.CashSession
import com.blackstore.domain.cash.CashSessionBook
import com.blackstore.domain.cash.RoleAuthorizationPolicy
import com.blackstore.domain.cash.SessionAction
import com.blackstore.domain.cash.StaffRole
import com.blackstore.domain.port.out.cash.CashSessionStore
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant

@Service
class CashSessionApplicationService(
    private val store: CashSessionStore,
) {
    private val roles = RoleAuthorizationPolicy()
    private val book = CashSessionBook()

    fun list(): List<CashSession> = store.list()

    fun open(
        actorId: Long,
        role: StaffRole,
        terminalId: Long,
        cashierId: Long,
        openingCash: BigDecimal,
        now: Instant = Instant.now(),
    ): CashSession {
        roles.assertAllowed(role, SessionAction.OPERATE, actorId, cashierId)
        return store.save(
            book.open(
                existing = store.list(),
                id = 0,
                terminalId = terminalId,
                cashierId = cashierId,
                openingCash = openingCash,
                openedAt = now,
            ),
        )
    }

    fun close(
        actorId: Long,
        role: StaffRole,
        sessionId: Long,
        declared: BigDecimal,
        reason: String,
        now: Instant = Instant.now(),
    ): CashSession {
        val current = store.list().firstOrNull { it.id == sessionId } ?: error("cash session $sessionId was not found")
        val action = if (actorId == current.cashierId) SessionAction.OPERATE else SessionAction.OVERRIDE
        roles.assertAllowed(role, action, actorId, current.cashierId)
        val (closed, audit) = book.close(current, declared, now, actorId, reason)
        val saved = store.save(closed)
        store.appendClosureAudit(audit)
        return saved
    }
}
