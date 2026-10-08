package com.blackstore.domain.port.out.pos

import com.blackstore.domain.identity.AuthenticatedStaff
import com.blackstore.domain.pos.PosContextResult

fun interface PosContextQuery {
    fun observe(staff: AuthenticatedStaff): PosContextResult
}
