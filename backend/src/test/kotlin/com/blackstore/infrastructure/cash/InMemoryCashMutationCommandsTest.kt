package com.blackstore.infrastructure.cash

import com.blackstore.domain.cash.*
import com.blackstore.domain.identity.*
import com.blackstore.infrastructure.counter.InMemoryCounterEntryStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class InMemoryCashMutationCommandsTest {
    private val now=Instant.parse("2026-10-06T12:00:00Z")
    private val staff=AuthenticatedStaff(StaffUserId(7),"Fixture cashier",StaffRole.CASHIER)
    private val users=object : StaffUserRepository {
        override fun findByLogin(login: String): StaffUser?=null
        override fun findById(id: StaffUserId)=StaffUser(id,"fixture","Fixture cashier",StaffRole.CASHIER,StaffAccountState.ACTIVE,"fixture-only")
    }
    private val visible=CashSession(1,10,staff.id.value,now,BigDecimal.ZERO)
    private val hidden=CashSession(2,11,8,now,BigDecimal.ZERO)
    private fun assertHiddenRegardlessOfOrder(sessions: List<CashSession>) {
        val cash=InMemoryCashSessionStore()
        sessions.forEach { cash.save(it) }
        val commands=InMemoryCashMutationCommands(cash,InMemoryCounterEntryStore(),users)
        assertEquals(CashMutationResult.Rejected(CashMutationFailure.NotVisible),
            commands.open(staff,hidden.terminalId,staff.id.value,BigDecimal.ZERO,null,now))
        assertEquals(sessions,cash.list())
    }
    @Test fun visibleCashierBlockerInsertedBeforeHiddenTerminalBlockerStillReturnsNotVisible() =
        assertHiddenRegardlessOfOrder(listOf(visible,hidden))
    @Test fun hiddenTerminalBlockerInsertedBeforeVisibleCashierBlockerReturnsSameNotVisible() =
        assertHiddenRegardlessOfOrder(listOf(hidden,visible))
    @Test fun entirelyVisibleBlockersReturnConflictWithoutMutating() {
        val cash=InMemoryCashSessionStore()
        cash.save(visible)
        val commands=InMemoryCashMutationCommands(cash,InMemoryCounterEntryStore(),users)
        assertEquals(CashMutationResult.Rejected(CashMutationFailure.Conflict),
            commands.open(staff,hidden.terminalId,staff.id.value,BigDecimal.ZERO,null,now))
        assertEquals(listOf(visible),cash.list())
    }
}
