package com.blackstore.application.identity

import com.blackstore.domain.identity.*
import com.blackstore.domain.cash.CashMutationResult
import com.blackstore.domain.cash.CashRejectionSource
import com.blackstore.domain.model.OperationQuadruple

class AuthorizeStaffAction(private val ownership: StaffOwnershipQuery, private val audit: SecurityAuditPort) {
    private val policy=StaffAuthorizationPolicy()
    /** Commands return only after rollback/connection release; denial audit is an independent write. */
    fun recordCashDenial(staff: AuthenticatedStaff, result: CashMutationResult<*>) {
        if (result is CashMutationResult.Rejected && result.source == CashRejectionSource.Authorization)
            audit.record(SecurityAuditEvent.AUTHORIZATION_DENIED, staff.id)
    }
    fun reserve(staff: AuthenticatedStaff, identity: OperationQuadruple, submittedCashId: Long, existingCashId: Long?, reason: String?) {
        cash(staff,StaffPermission.SaleReserve,submittedCashId,reason)
        val persisted = ownership.sale(identity)
        if (persisted != null) {
            check(staff,StaffPermission.SaleReserve,persisted,reason)
            if (persisted.id != submittedCashId || (existingCashId != null && existingCashId != persisted.id)) deny(staff,StaffSecurityFailure.NOT_FOUND)
        } else if (existingCashId != null) deny(staff,StaffSecurityFailure.NOT_FOUND)
    }
    fun permission(staff: AuthenticatedStaff, permission: StaffPermission) {
        if(!policy.permits(staff.role,permission)) deny(staff,StaffSecurityFailure.FORBIDDEN)
    }
    fun cash(staff: AuthenticatedStaff, permission: StaffPermission, id: Long, reason: String?=null) = check(staff,permission,ownership.cash(id),reason)
    fun sale(staff: AuthenticatedStaff, permission: StaffPermission, operationId: String, reason: String?=null) = check(staff,permission,ownership.sale(operationId),reason)
    fun sale(staff: AuthenticatedStaff, permission: StaffPermission, identity: OperationQuadruple, reason: String?=null) = check(staff,permission,ownership.sale(identity),reason)
    fun payment(staff: AuthenticatedStaff, id: Long, identity: OperationQuadruple, reason: String?) = check(staff,StaffPermission.PaymentReverse,ownership.payment(id,identity),reason)
    fun open(staff: AuthenticatedStaff, cashierId: Long, reason: String?) {
        permission(staff,StaffPermission.CashSessionOpen)
        check(staff,StaffPermission.CashSessionOpen,OwnedCashSession(0,StaffUserId(cashierId),com.blackstore.domain.cash.CashSessionStatus.OPEN),reason)
        if(!ownership.eligibleCashier(StaffUserId(cashierId))) deny(staff,StaffSecurityFailure.NOT_FOUND)
    }
    private fun check(staff: AuthenticatedStaff, permission: StaffPermission, cash: OwnedCashSession?, reason: String?): OwnedCashSession {
        when(policy.decide(staff,permission,cash,reason)) {
            AuthorizationDecision.ALLOW -> return cash!!
            AuthorizationDecision.NOT_FOUND -> deny(staff,StaffSecurityFailure.NOT_FOUND)
            AuthorizationDecision.REASON_REQUIRED -> deny(staff,StaffSecurityFailure.VALIDATION)
            AuthorizationDecision.FORBIDDEN, AuthorizationDecision.UNKNOWN -> deny(staff,StaffSecurityFailure.FORBIDDEN)
        }
    }
    private fun deny(staff: AuthenticatedStaff, failure: StaffSecurityFailure): Nothing { audit.record(SecurityAuditEvent.AUTHORIZATION_DENIED,staff.id); throw StaffSecurityException(failure) }
}
