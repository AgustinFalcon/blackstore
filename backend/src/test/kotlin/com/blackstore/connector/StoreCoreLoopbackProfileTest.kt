package com.blackstore.connector

import com.blackstore.application.storecore.StoreCoreDispatchGuard
import com.blackstore.application.storecore.StoreCoreIntegrationProperties
import com.blackstore.domain.exception.BlockedStoreCoreIntegrationException
import com.blackstore.domain.model.StoreCoreCanonicalContract
import com.blackstore.domain.model.StoreCoreContractRef
import com.blackstore.domain.port.out.storecore.StoreCoreCatalogPort
import com.blackstore.domain.port.out.storecore.StoreCoreInventoryPort
import com.blackstore.infrastructure.storecore.FixtureCatalogAdapter
import com.blackstore.infrastructure.storecore.LoopbackCatalogAdapter
import com.blackstore.infrastructure.storecore.TransportStoreCoreInventoryAdapter
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

class StoreCoreLoopbackProfileTest {
    @Test
    fun defaultSettingsStayFailClosed() {
        val settings =
            StoreCoreIntegrationProperties(
                contract =
                    StoreCoreIntegrationProperties.Contract(
                        canonicalPath = StoreCoreCanonicalContract.CANONICAL_PATH,
                        version = StoreCoreCanonicalContract.VERSION,
                        sha256 = StoreCoreCanonicalContract.SHA256,
                    ),
            ).toSettings()
        assertThrows(BlockedStoreCoreIntegrationException::class.java) {
            StoreCoreDispatchGuard.assertCanDispatch(settings)
        }
    }

    @Test
    fun loopbackProfileSettingsPassOnlyForLocalhost() {
        val properties =
            StoreCoreIntegrationProperties(
                integration = StoreCoreIntegrationProperties.Integration(enabled = true, mode = "loopback"),
                transport =
                    StoreCoreIntegrationProperties.Transport(
                        killSwitch = false,
                        tlsRequired = false,
                        allowPlainLoopback = true,
                        baseUrl = "http://127.0.0.1:8080",
                        identityRef = "blackstore-service-local",
                        tokenRef = StoreCoreIntegrationProperties.LOCAL_LOOPBACK_TOKEN_REF,
                        clientInstanceId = "11111111-1111-1111-1111-111111111111",
                    ),
                control = StoreCoreIntegrationProperties.Control(capabilityActive = true, killSwitch = false),
                contract =
                    StoreCoreIntegrationProperties.Contract(
                        canonicalPath = StoreCoreCanonicalContract.CANONICAL_PATH,
                        version = StoreCoreCanonicalContract.VERSION,
                        sha256 = StoreCoreCanonicalContract.SHA256,
                    ),
            )
        StoreCoreDispatchGuard.assertCanDispatch(properties.toSettings())
        assertThrows(BlockedStoreCoreIntegrationException::class.java) {
            StoreCoreDispatchGuard.assertCanDispatch(properties.toSettings().copy(baseUrl = "http://example.com"))
        }
    }

    @Test
    fun catalogParseKeepsPriceVersionFromStoreCore() {
        val json =
            """
            {"code":200,"data":{"catalogVersion":"cat-1","generatedAt":"2026-09-24T00:00:00Z","validUntil":"2026-12-31T00:00:00Z","items":[
              {"variantId":9,"sku":"SKU-9","name":"Te","priceVersion":"pv-9","unitPrice":10.50,"active":true},
              {"variantId":10,"sku":"OFF","name":"Off","priceVersion":"pv-x","unitPrice":1,"active":false}
            ]}}
            """.trimIndent()
        val snapshot =
            LoopbackCatalogAdapter.parse(
                ObjectMapper().readTree(json),
                StoreCoreContractRef(StoreCoreCanonicalContract.CANONICAL_PATH, StoreCoreCanonicalContract.VERSION),
                Instant.parse("2026-09-24T12:00:00Z"),
            )
        requireNotNull(snapshot)
        assertEquals("cat-1", snapshot.version)
        assertEquals(1, snapshot.items.size)
        assertEquals("9", snapshot.items.single().variantId)
        assertEquals("pv-9", snapshot.items.single().priceVersion)
        assertTrue(!snapshot.stale)
    }
}

@SpringBootTest
class DefaultStoreCoreBeansTest {
    @Autowired
    private lateinit var catalog: StoreCoreCatalogPort

    @Test
    fun defaultCatalogStaysOnTheFixture() {
        assertInstanceOf(FixtureCatalogAdapter::class.java, catalog)
    }
}

@SpringBootTest
@ActiveProfiles("loopback")
class LoopbackStoreCoreBeansTest {
    @Autowired
    private lateinit var catalog: StoreCoreCatalogPort

    @Autowired
    private lateinit var inventory: StoreCoreInventoryPort

    @Test
    fun loopbackProfileUsesHttpPorts() {
        assertInstanceOf(LoopbackCatalogAdapter::class.java, catalog)
        assertInstanceOf(TransportStoreCoreInventoryAdapter::class.java, inventory)
    }
}
