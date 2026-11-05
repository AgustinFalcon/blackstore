package com.blackstore.application.accounting

import com.blackstore.domain.accounting.ExpenseCommandProjectionResult
import com.blackstore.domain.identity.ResolvedStaffSession
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import java.util.UUID

/** Query boundary owns a single authority/source snapshot; no mutation port is available. */
fun interface ExpenseCommandProjectionQuery {
    fun read(session: ResolvedStaffSession, commandId: UUID): ExpenseCommandProjectionResult
}

@Service
class ExpenseCommandProjectionService(private val query: ObjectProvider<ExpenseCommandProjectionQuery>) {
    fun read(session: ResolvedStaffSession, commandId: UUID): ExpenseCommandProjectionResult =
        query.ifAvailable?.read(session, commandId) ?: ExpenseCommandProjectionResult.Unavailable
}
