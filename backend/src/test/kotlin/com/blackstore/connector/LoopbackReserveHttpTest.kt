package com.blackstore.connector

import com.blackstore.application.storecore.StoreCoreEnvelopeValidator
import com.blackstore.application.storecore.StoreCoreIntegrationProperties
import com.blackstore.application.storecore.StoreCoreTransportSettings
import com.blackstore.domain.exception.StoreCoreRemoteFault
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.model.StoreCoreCanonicalContract
import com.blackstore.domain.model.StoreCoreOperationState
import com.blackstore.domain.port.out.storecore.ReserveInventoryCommand
import com.blackstore.domain.port.out.storecore.ReserveLineCommand
import com.blackstore.infrastructure.storecore.StoreCoreHttpTransport
import com.blackstore.infrastructure.storecore.TransportStoreCoreInventoryAdapter
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetSocketAddress

class LoopbackReserveHttpTest {

    @Test
    fun reservePostCarriesTheCatalogVariantAndARetiredResponseDoesNotGet() {
        val posts = mutableListOf<String>()
        val gets = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(StoreCoreCanonicalContract.CANONICAL_PATH) { exchange ->
            val path = exchange.requestURI.path
            if (exchange.requestMethod == "GET") {
                gets += path
            }
            if (exchange.requestMethod == "POST") {
                posts += exchange.requestBody.readAllBytes().decodeToString()
            }
            val body =
                if (posts.size > 1) {
                    """{"code":410,"data":null,"errorCode":"OPERATION_RETIRED","retryable":false,"message":"retired","traceId":"t-410"}"""
                } else {
                    """{"code":200,"traceId":"t-200","data":{"state":"RESERVED","contractVersion":"1.0.0-draft","receipt":"rcpt-9","reservationRef":"res-9","acceptedPriceVersions":["pv-9"]}}"""
                }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(if (posts.size > 1) 410 else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val adapter = adapter("http://127.0.0.1:${server.address.port}")
            val reserved = adapter.reserve(command("op-9", "sale-9"))
            assertEquals(StoreCoreOperationState.RESERVED, reserved.state)
            assertEquals("pv-9", reserved.acceptedPriceVersions.single())
            assertTrue(posts.single().contains(""""variantId":"9""""))
            assertTrue(posts.single().contains(""""expectedPriceVersion":"pv-9""""))
            assertEquals(0, gets.size)

            val fault =
                assertThrows<StoreCoreRemoteFault> {
                    adapter.reserve(command("op-410", "sale-410"))
                }
            assertEquals("OPERATION_RETIRED", fault.errorCode)
            assertEquals(false, fault.retryable)
            assertEquals(0, gets.size)
            assertEquals(2, posts.size)
        } finally {
            server.stop(0)
        }
    }

    private fun command(operationId: String, saleId: String) =
        ReserveInventoryCommand(
            quadruple = OperationQuadruple("11111111-1111-1111-1111-111111111111", "terminal-1", saleId, operationId),
            catalogVersion = "cat-http",
            lines = listOf(ReserveLineCommand("9", 1, "pv-9")),
        )

    private fun adapter(baseUrl: String): TransportStoreCoreInventoryAdapter {
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
        return TransportStoreCoreInventoryAdapter(transport, StoreCoreEnvelopeValidator(), jacksonObjectMapper())
    }
}
