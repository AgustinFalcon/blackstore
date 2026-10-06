package com.blackstore.domain.cash

import com.blackstore.domain.exception.ForbiddenOperationException

/**
 * CASHIER operates only their own session. SUPERVISOR may override.
 * OWNER configures and reads reports. AUDITOR reads immutable evidence and does not mutate.
 */
class RoleAuthorizationPolicy {

    fun assertAllowed(
        role: StaffRole,
        action: SessionAction,
        actorId: Long,
        sessionCashierId: Long? = null,
    ) {
        val ownsSession = sessionCashierId != null && actorId == sessionCashierId
        val allowed =
            when (action) {
                SessionAction.OPERATE ->
                    (role == StaffRole.CASHIER && ownsSession) || role == StaffRole.SUPERVISOR || role == StaffRole.OWNER
                SessionAction.OVERRIDE -> role == StaffRole.SUPERVISOR || role == StaffRole.OWNER
                SessionAction.VIEW_REPORTS -> role == StaffRole.SUPERVISOR || role == StaffRole.OWNER || role == StaffRole.AUDITOR
                SessionAction.CONFIGURE -> role == StaffRole.OWNER
                SessionAction.READ_AUDIT -> role == StaffRole.AUDITOR || role == StaffRole.OWNER
            }
        if (!allowed) {
            throw ForbiddenOperationException("role $role cannot $action")
        }
    }
}
