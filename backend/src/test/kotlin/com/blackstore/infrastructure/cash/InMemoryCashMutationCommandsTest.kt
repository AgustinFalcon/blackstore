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
        assertEquals(CashMutationResult.Rejected(CashMutationFailure.NotVisible,CashRejectionSource.Authorization),
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
    @Test fun inactiveActorAndIneligibleOwnerHaveAuthorizationProvenanceAndIndependentAudit() {
        for(inactiveActor in listOf(true,false)) {
            val actor=if(inactiveActor) staff else staff.copy(role=StaffRole.OWNER)
            val ownerId=if(inactiveActor) actor.id.value else 8L
            val repository=object : StaffUserRepository {
                override fun findByLogin(login: String): StaffUser?=null
                override fun findById(id: StaffUserId)=StaffUser(id,"fixture","Fixture",if(id==actor.id) actor.role else StaffRole.AUDITOR,if(inactiveActor && id==actor.id) StaffAccountState.INACTIVE else StaffAccountState.ACTIVE,"fixture-only")
            }
            val cash=InMemoryCashSessionStore()
            val events=mutableListOf<SecurityAuditEvent>()
            val audit=object : SecurityAuditPort {
                override fun record(event: SecurityAuditEvent,actor: StaffUserId?,target: StaffUserId?) { assertEquals(emptyList<CashSession>(),cash.list());events.add(event) }
            }
            val authorization=com.blackstore.application.identity.AuthorizeStaffAction(org.mockito.Mockito.mock(StaffOwnershipQuery::class.java),audit)
            val application=com.blackstore.application.cash.CashSessionApplicationService(cash,authorization,InMemoryCashMutationCommands(cash,InMemoryCounterEntryStore(),repository))
            val failure=org.junit.jupiter.api.assertThrows<CashMutationException> { application.open(actor,10,ownerId,BigDecimal.ZERO,"override") }
            assertEquals(if(inactiveActor) CashMutationFailure.Forbidden else CashMutationFailure.NotVisible,failure.failure)
            assertEquals(CashRejectionSource.Authorization,failure.source)
            assertEquals(listOf(SecurityAuditEvent.AUTHORIZATION_DENIED),events)
            assertEquals(emptyList<CashSession>(),cash.list())
        }
    }
}
