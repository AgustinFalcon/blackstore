package com.blackstore.application

import com.blackstore.application.identity.AuthorizeStaffAction
import com.blackstore.domain.cash.*
import com.blackstore.domain.identity.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

class CashDenialAuditTest {
    private val staff = AuthenticatedStaff(StaffUserId(7),"Cashier",StaffRole.CASHIER)
    @Test fun onlyAuthorizationProvenanceProducesOneIndependentDenialAudit() {
        val events = mutableListOf<Pair<SecurityAuditEvent,StaffUserId?>>()
        val audit = object : SecurityAuditPort {
            override fun record(event: SecurityAuditEvent,actor: StaffUserId?,target: StaffUserId?) { events.add(event to actor) }
        }
        val authorization = AuthorizeStaffAction(mock(StaffOwnershipQuery::class.java),audit)
        for (failure in CashMutationFailure.entries) {
            val ordinary = CashMutationResult.Rejected(failure)
            authorization.recordCashDenial(staff,ordinary)
            val thrown = org.junit.jupiter.api.assertThrows<CashMutationException> { ordinary.recordOrThrow() }
            assertEquals(failure,thrown.failure)
            assertEquals(CashRejectionSource.Mutation,thrown.source)
        }
        authorization.recordCashDenial(staff,CashMutationResult.Applied(Unit))
        assertTrue(events.isEmpty())
        for (failure in listOf(CashMutationFailure.NotVisible,CashMutationFailure.Validation,CashMutationFailure.Forbidden)) {
            val denied = CashMutationResult.Rejected(failure,CashRejectionSource.Authorization)
            authorization.recordCashDenial(staff,denied)
            val thrown = org.junit.jupiter.api.assertThrows<CashMutationException> { denied.recordOrThrow() }
            assertEquals(failure,thrown.failure)
            assertEquals(CashRejectionSource.Authorization,thrown.source)
        }
        assertEquals(List(3) { SecurityAuditEvent.AUTHORIZATION_DENIED to staff.id },events)
    }
}
