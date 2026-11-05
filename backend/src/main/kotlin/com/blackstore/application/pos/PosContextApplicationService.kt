package com.blackstore.application.pos

import com.blackstore.domain.identity.*
import com.blackstore.domain.port.out.pos.PosContextQuery
import com.blackstore.domain.pos.PosContextResult
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service

@Service
class PosContextApplicationService(private val queries: ObjectProvider<PosContextQuery>) {
    fun observe(staff: AuthenticatedStaff): PosContextResult {
        if (!StaffAuthorizationPolicy().permits(staff.role, StaffPermission.WorkspaceRead))
            throw StaffSecurityException(StaffSecurityFailure.FORBIDDEN)
        return queries.ifAvailable?.observe(staff) ?: PosContextResult.Unavailable
    }
}
