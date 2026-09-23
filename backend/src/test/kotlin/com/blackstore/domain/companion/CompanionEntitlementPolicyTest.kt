package com.blackstore.domain.companion

import com.blackstore.domain.exception.EntitlementDeniedException
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class CompanionEntitlementPolicyTest {

    private val policy = CompanionEntitlementPolicy()
    private val installationRef: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val expected =
        ExpectedCompanionBinding(
            storecoreInstallationRef = installationRef,
            serviceClientRef = "blackstore-service-local",
        )

    @Test
    fun enabledMatchingCompanionPasses() {
        assertDoesNotThrow {
            policy.assertOperational(enabledInstallation(), companionCount = 1, expected = expected)
        }
    }

    @Test
    fun disabledEntitlementFailsClosed() {
        assertThrows<EntitlementDeniedException> {
            policy.assertOperational(
                enabledInstallation().copy(entitlementState = EntitlementState.DISABLED),
                companionCount = 1,
                expected = expected,
            )
        }
    }

    @Test
    fun suspendedEntitlementFailsClosed() {
        assertThrows<EntitlementDeniedException> {
            policy.assertOperational(
                enabledInstallation().copy(entitlementState = EntitlementState.SUSPENDED),
                companionCount = 1,
                expected = expected,
            )
        }
    }

    @Test
    fun installationMismatchFailsClosed() {
        assertThrows<EntitlementDeniedException> {
            policy.assertOperational(
                enabledInstallation(),
                companionCount = 1,
                expected = expected.copy(storecoreInstallationRef = UUID.randomUUID()),
            )
        }
    }

    @Test
    fun serviceIdentityMismatchFailsClosed() {
        assertThrows<EntitlementDeniedException> {
            policy.assertOperational(
                enabledInstallation(),
                companionCount = 1,
                expected = expected.copy(serviceClientRef = "other-service"),
            )
        }
    }

    @Test
    fun missingOrExtraCompanionFailsClosed() {
        assertThrows<EntitlementDeniedException> {
            policy.assertOperational(stored = null, companionCount = 0, expected = expected)
        }
        assertThrows<EntitlementDeniedException> {
            policy.assertOperational(enabledInstallation(), companionCount = 2, expected = expected)
        }
    }

    @Test
    fun serviceIdentityIsNotACredential() {
        val fieldNames = ServiceIdentity::class.java.declaredFields.map { it.name.lowercase() }
        assertEquals(listOf("clientref"), fieldNames)
        val forbidden = listOf("password", "secret", "token", "credential", "apikey")
        assertTrue(fieldNames.none { name -> forbidden.any { name.contains(it) } })
    }

    private fun enabledInstallation() =
        CompanionInstallation(
            storecoreInstallationRef = installationRef,
            entitlementState = EntitlementState.ENABLED,
            environment = CompanionEnvironment.TEST,
            serviceIdentity = ServiceIdentity("blackstore-service-local"),
        )
}
