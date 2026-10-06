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
import com.blackstore.application.identity.AuthorizeStaffAction
import com.blackstore.domain.identity.*

@Service
class CashSessionApplicationService(
    private val store: CashSessionStore,
    private val authorization: AuthorizeStaffAction? = null,
) {
    private val roles = RoleAuthorizationPolicy()
    private val book = CashSessionBook()

    fun list(): List<CashSession> = store.list()

    fun list(staff: AuthenticatedStaff): List<CashSession> {
        authority().permission(staff, StaffPermission.CashSessionList)
        return store.list().filter { staff.role != StaffRole.CASHIER || it.cashierId == staff.id.value }
    }
    fun open(staff: AuthenticatedStaff, terminalId: Long, cashierId: Long, openingCash: BigDecimal, reason: String?): CashSession {
        authority().open(staff,cashierId,reason)
        return open(staff.id.value,staff.role,terminalId,cashierId,openingCash).also {
            store.appendClosureAudit(com.blackstore.domain.cash.CashAuditEvent(it.id,staff.id.value,com.blackstore.domain.cash.CashAuditEventType.CASH_SESSION_OPENED,reason ?: "own cash session"))
        }
    }
    fun close(staff: AuthenticatedStaff, sessionId: Long, declared: BigDecimal, reason: String): CashSession {
        authority().cash(staff,StaffPermission.CashSessionClose,sessionId,reason)
        return close(staff.id.value,staff.role,sessionId,declared,reason.takeIf { it.isNotBlank() } ?: "own cash session closure")
    }
    private fun authority()=authorization ?: throw StaffSecurityException(StaffSecurityFailure.IDENTITY_UNAVAILABLE)

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
