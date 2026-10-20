package com.blackstore.infrastructure.sales

import com.blackstore.domain.accounting.AccountingCommandFailure
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkerLifecycleGateTest {
    @Test
    fun onlyExpectedNonWritingLifecycleStatesIdleTheWorker() {
        assertTrue(WorkerLifecycleGate.isIdle(AccountingCommandFailure.NotActivated))
        assertTrue(WorkerLifecycleGate.isIdle(AccountingCommandFailure.Paused))
        AccountingCommandFailure.entries
            .filterNot { it in setOf(AccountingCommandFailure.NotActivated, AccountingCommandFailure.Paused) }
            .forEach { assertFalse(WorkerLifecycleGate.isIdle(it), it.wire) }
    }
}
