package com.blackstore.domain

import com.blackstore.application.storecore.StoreCoreContractCompatibilityGuard
import com.blackstore.domain.model.StoreCoreCanonicalContract
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class StoreCoreCanonicalContractTest {
    @Test
    fun pinnedDigestAcceptsCurrentCanonicalIdentity() {
        StoreCoreCanonicalContract.assertCompatible(
            StoreCoreCanonicalContract.CANONICAL_PATH,
            StoreCoreCanonicalContract.VERSION,
            StoreCoreCanonicalContract.SHA256.uppercase(),
        )
        assertEquals(64, StoreCoreCanonicalContract.SHA256.length)
    }

    @Test
    fun breakingDigestOrVersionFailsClosed() {
        assertThrows(IllegalStateException::class.java) {
            StoreCoreCanonicalContract.assertCompatible(
                StoreCoreCanonicalContract.CANONICAL_PATH,
                StoreCoreCanonicalContract.VERSION,
                "aba6974723b47d2f5e28a170d3f6e41ecb3387c04e10debcdea097b2bff99bda",
            )
        }
        assertThrows(IllegalStateException::class.java) {
            StoreCoreCanonicalContract.assertCompatible(
                StoreCoreCanonicalContract.CANONICAL_PATH,
                "1.0.1",
                StoreCoreCanonicalContract.SHA256,
            )
        }
        assertThrows(IllegalStateException::class.java) {
            StoreCoreContractCompatibilityGuard(
                StoreCoreCanonicalContract.CANONICAL_PATH,
                StoreCoreCanonicalContract.VERSION,
                "aba6974723b47d2f5e28a170d3f6e41ecb3387c04e10debcdea097b2bff99bda",
            )
        }
    }
}
