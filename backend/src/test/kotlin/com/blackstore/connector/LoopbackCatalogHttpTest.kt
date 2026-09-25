package com.blackstore.connector

import com.blackstore.application.storecore.StoreCoreIntegrationProperties
import com.blackstore.application.storecore.StoreCoreTransportSettings
import com.blackstore.domain.catalog.CatalogReserveLinePolicy
import com.blackstore.domain.catalog.CatalogSalePolicy
import com.blackstore.domain.exception.ForbiddenOperationException
import com.blackstore.domain.model.StoreCoreCanonicalContract
import com.blackstore.domain.model.StoreCoreContractRef
import com.blackstore.domain.port.out.storecore.ReserveLineCommand
import com.blackstore.domain.sales.TicketLine
import com.blackstore.infrastructure.storecore.LoopbackCatalogAdapter
import com.blackstore.infrastructure.storecore.StoreCoreHttpTransport
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.time.Instant

class LoopbackCatalogHttpTest {

    private val now = Instant.parse("2026-09-24T12:00:00Z")

    @Test
    fun localStubCatalogSuppliesTheReserveLineAndAClosedStubBlocksTheSale() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val baseUrl: String
        server.createContext("${StoreCoreCanonicalContract.CANONICAL_PATH}/catalog") { exchange ->
            val body =
                """
                {"code":200,"data":{"catalogVersion":"cat-http","generatedAt":"2026-09-24T00:00:00Z","validUntil":"2026-12-31T00:00:00Z","items":[
                  {"variantId":9,"sku":"SKU-9","name":"Te","priceVersion":"pv-9","unitPrice":10.50,"active":true}
                ]}}
                """.trimIndent()
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
        try {
            val adapter = adapter(baseUrl)
            val snapshot = requireNotNull(adapter.currentSnapshot())
            val resolved =
                CatalogReserveLinePolicy().resolve(
                    snapshot,
                    listOf(ReserveLineCommand("variant-1", 1, "price-v1")),
                    listOf(
                        TicketLine(
                            sku = "SKU-9",
                            productName = "Te",
                            quantity = 1,
                            originalUnitPrice = BigDecimal("10.50"),
                            discountAmount = BigDecimal.ZERO,
                        ),
                    ),
                )
            assertEquals("9", resolved.single().variantId)
            assertEquals("pv-9", resolved.single().expectedPriceVersion)
        } finally {
            server.stop(0)
        }

        val closed = adapter(baseUrl)
        assertNull(closed.currentSnapshot())
        assertThrows<ForbiddenOperationException> {
            CatalogSalePolicy().assertSaleAllowed(closed.currentSnapshot(), now)
        }
    }

    private fun adapter(baseUrl: String): LoopbackCatalogAdapter {
        val settings =
            StoreCoreTransportSettings(
                enabled = true,
                killSwitch = false,
                capabilityActive = true,
                baseUrl = baseUrl,
                tlsRequired = false,
                allowPlainLoopback = true,
                identityRef = "blackstore-service-local",
                tokenRef = StoreCoreIntegrationProperties.LOCAL_LOOPBACK_TOKEN_REF,
                timeoutMs = 1000,
                maxRetries = 0,
                path = StoreCoreCanonicalContract.CANONICAL_PATH,
                version = StoreCoreCanonicalContract.VERSION,
                digest = StoreCoreCanonicalContract.SHA256,
            )
        val transport =
            StoreCoreHttpTransport(
                settings,
                resolveToken = { ref ->
                    if (ref == StoreCoreIntegrationProperties.LOCAL_LOOPBACK_TOKEN_REF) {
                        StoreCoreIntegrationProperties.LOCAL_LOOPBACK_TOKEN
                    } else {
                        null
                    }
                },
            )
        return LoopbackCatalogAdapter(
            transport = transport,
            clientInstanceId = "11111111-1111-1111-1111-111111111111",
            contract = StoreCoreContractRef(StoreCoreCanonicalContract.CANONICAL_PATH, StoreCoreCanonicalContract.VERSION),
            mapper = ObjectMapper(),
            clock = { now },
        )
    }
}
