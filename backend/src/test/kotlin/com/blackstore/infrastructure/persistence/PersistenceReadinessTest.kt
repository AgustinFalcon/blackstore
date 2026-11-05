package com.blackstore.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PersistenceReadinessTest {
    @Test
    fun scheduledPersistenceStartsOnlyAfterMigrationAndSeedComplete() {
        val readiness = PersistenceReadiness()

        assertFalse(readiness.isReady())
        readiness.markReady()
        assertTrue(readiness.isReady())
    }
}
