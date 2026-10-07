package com.blackstore.domain

import com.blackstore.domain.cash.*
import com.blackstore.domain.identity.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Instant

class CashMutationPolicyTest {
    private val policy=CashMutationPolicy()
    private val now=Instant.parse("2026-10-06T12:00:00Z")
    private val own=CashSession(1,1,7,now,BigDecimal.ZERO)
    private fun staff(role: StaffRole)=AuthenticatedStaff(StaffUserId(7),"Operator",role)
    @Test fun visibilityPrecedesStateAndOverrideEligibility() {
        val closed=own.copy(status=CashSessionStatus.CLOSED,closedAt=now,closingCashDeclared=BigDecimal.ZERO)
        for (session in listOf(own,closed)) {
            assertNull(policy.authorize(staff(StaffRole.CASHIER),StaffPermission.ExpenseRecord,session,"expense"))
            assertEquals(CashMutationFailure.NotVisible,policy.authorize(staff(StaffRole.CASHIER),StaffPermission.ExpenseRecord,session.copy(cashierId=8),"expense"))
            assertEquals(CashMutationFailure.Validation,policy.authorize(staff(StaffRole.SUPERVISOR),StaffPermission.CashSessionClose,session.copy(cashierId=8),null))
            assertNull(policy.authorize(staff(StaffRole.OWNER),StaffPermission.CashSessionClose,session.copy(cashierId=8),"override"))
        }
        assertEquals(CashMutationFailure.Conflict,assertThrows<CashMutationException> { policy.requireOpen(closed) }.failure)
        assertEquals(CashMutationFailure.NotVisible,policy.authorize(staff(StaffRole.CASHIER),StaffPermission.ExpenseRecord,null,"expense"))
        assertEquals(CashMutationFailure.NotVisible,policy.authorize(staff(StaffRole.CASHIER),StaffPermission.ExpenseRecord,own.copy(status=CashSessionStatus.UNKNOWN),"expense"))
        for(role in listOf(StaffRole.AUDITOR,StaffRole.UNKNOWN)) assertEquals(CashMutationFailure.Forbidden,policy.authorize(staff(role),StaffPermission.CashSessionClose,own,"closure"))
    }
    @Test fun sidConsumersRetainTheirPreviousEligibilityRule() {
        val cash=OwnedCashSession(1,StaffUserId(7),CashSessionStatus.CLOSED)
        assertEquals(AuthorizationDecision.NOT_FOUND,StaffAuthorizationPolicy().decide(staff(StaffRole.CASHIER),StaffPermission.ExpenseRecord,cash,"expense"))
        assertEquals(AuthorizationDecision.ALLOW,StaffAuthorizationPolicy().decideCashMutation(staff(StaffRole.CASHIER),StaffPermission.ExpenseRecord,cash,"expense"))
    }
    @Test fun moneyIsExactAndNeverLetsPostgresRound() {
        assertEquals(BigDecimal("0.00"),policy.money(BigDecimal.ZERO))
        assertEquals(BigDecimal("0.01"),policy.money(BigDecimal("0.01"),true))
        assertEquals(BigDecimal("999999999999.99"),policy.money(BigDecimal("999999999999.99")))
        for(value in listOf("-0.01","0.001","1000000000000.00")) assertEquals(CashMutationFailure.Validation,assertThrows<CashMutationException> { policy.money(BigDecimal(value)) }.failure)
        assertEquals(CashMutationFailure.Validation,assertThrows<CashMutationException> { policy.money(BigDecimal.ZERO,true) }.failure)
    }
}
