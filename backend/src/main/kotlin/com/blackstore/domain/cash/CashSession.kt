package com.blackstore.domain.cash

import com.blackstore.domain.exception.ForbiddenOperationException
import java.math.BigDecimal
import java.time.Instant

enum class CashSessionStatus {
    OPEN,
    CLOSED,
    RECONCILIATION_REQUIRED,
    UNKNOWN;
    companion object { fun fromWire(value: String?): CashSessionStatus = entries.firstOrNull { it.name == value } ?: UNKNOWN }
}

data class CashSession(
    val id: Long,
    val terminalId: Long,
    val cashierId: Long,
    val openedAt: Instant,
    val openingCash: BigDecimal,
    val status: CashSessionStatus = CashSessionStatus.OPEN,
    val closedAt: Instant? = null,
    val closingCashDeclared: BigDecimal? = null,
) {
    init {
        require(openingCash.signum() >= 0) { "opening cash cannot be negative" }
        if (status == CashSessionStatus.OPEN) {
            require(closedAt == null && closingCashDeclared == null) { "open session has no closure" }
        } else if (status != CashSessionStatus.UNKNOWN) {
            require(closedAt != null && closingCashDeclared != null && !closedAt.isBefore(openedAt)) {
                "closed session requires closure timestamp and declared cash"
            }
        }
    }
}

enum class CashAuditEventType { CASH_SESSION_OPENED, CASH_SESSION_CLOSED, EXPENSE_RECORDED, UNKNOWN }

data class CashAuditEvent(
    val sessionId: Long,
    val actorId: Long,
    val eventType: CashAuditEventType,
    val reason: String,
)

class CashSessionBook {

    fun open(
        existing: List<CashSession>,
        id: Long,
        terminalId: Long,
        cashierId: Long,
        openingCash: BigDecimal,
        openedAt: Instant,
    ): CashSession {
        if (existing.any { it.terminalId == terminalId && it.status == CashSessionStatus.OPEN }) {
            throw ForbiddenOperationException("terminal $terminalId already has an open session")
        }
        return CashSession(
            id = id,
            terminalId = terminalId,
            cashierId = cashierId,
            openedAt = openedAt,
            openingCash = openingCash,
        )
    }

    fun close(
        session: CashSession,
        declared: BigDecimal,
        closedAt: Instant,
        actorId: Long,
        reason: String,
    ): Pair<CashSession, CashAuditEvent> {
        require(session.status == CashSessionStatus.OPEN) { "only an open session can close" }
        require(reason.isNotBlank()) { "closure reason is required" }
        val closed =
            session.copy(
                status = CashSessionStatus.CLOSED,
                closedAt = closedAt,
                closingCashDeclared = declared,
            )
        return closed to
            CashAuditEvent(
                sessionId = session.id,
                actorId = actorId,
                eventType = CashAuditEventType.CASH_SESSION_CLOSED,
                reason = reason,
            )
    }
}
