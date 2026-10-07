package com.blackstore.domain

import com.blackstore.domain.accounting.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AccountingWriterAdmissionTest {
    private val policy = AccountingWriterAdmissionPolicy()

    @Test fun `legacy never resumes after activation`() {
        assertNull(policy.failure(AccountingRuntimeState.PreActivation, AccountingWriterKind.Legacy))
        for (state in listOf(AccountingRuntimeState.Active, AccountingRuntimeState.Paused))
            assertEquals(AccountingCommandFailure.LegacyContractDisabled, policy.failure(state, AccountingWriterKind.Legacy))
    }

    @Test fun `new v2 work requires active while worker honors pause`() {
        assertEquals(AccountingCommandFailure.NotActivated, policy.failure(AccountingRuntimeState.PreActivation, AccountingWriterKind.VersionTwo))
        assertNull(policy.failure(AccountingRuntimeState.Active, AccountingWriterKind.VersionTwo))
        for (writer in listOf(AccountingWriterKind.VersionTwo, AccountingWriterKind.Worker))
            assertEquals(AccountingCommandFailure.Paused, policy.failure(AccountingRuntimeState.Paused, writer))
        for (state in listOf(AccountingRuntimeState.PreActivation, AccountingRuntimeState.Active))
            assertNull(policy.failure(state, AccountingWriterKind.Worker))
    }

    @Test fun `unknown lifecycle or writer cannot be admitted`() {
        AccountingWriterKind.entries.forEach { assertEquals(AccountingCommandFailure.Unavailable, policy.failure(AccountingRuntimeState.Unknown, it)) }
        AccountingRuntimeState.entries.forEach { assertEquals(AccountingCommandFailure.Unavailable, policy.failure(it, AccountingWriterKind.Unknown)) }
    }
}
