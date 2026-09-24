package com.blackstore.connector

import com.blackstore.application.storecore.StoreCoreControlPlane
import com.blackstore.application.storecore.StoreCoreDispatchGuard
import com.blackstore.application.storecore.StoreCoreEnvelopeValidator
import com.blackstore.application.storecore.StoreCoreTransportSettings
import com.blackstore.domain.exception.BlockedStoreCoreIntegrationException
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.model.StoreCoreCanonicalContract
import com.blackstore.domain.model.StoreCoreOperationState
import com.blackstore.domain.port.out.storecore.CommitInventoryCommand
import com.blackstore.domain.port.out.storecore.ReconcileQuery
import com.blackstore.domain.port.out.storecore.ReleaseInventoryCommand
import com.blackstore.domain.port.out.storecore.ReserveInventoryCommand
import com.blackstore.infrastructure.storecore.StoreCoreHttpTransport
import com.blackstore.infrastructure.storecore.TransportStoreCoreInventoryAdapter
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class StoreCoreContractSequenceTest {

    private val executor = Executors.newCachedThreadPool()
    private var server: HttpServer? = null
    private val hits = AtomicInteger()
    private val lastHeaders = mutableMapOf<String, String>()

    @AfterEach
    fun stop() {
        server?.stop(0)
        executor.shutdownNow()
    }

    @Test
    fun loopbackSequenceUsesIdentityHeadersAndPinnedFixtures() {
        startServer()
        val transport = StoreCoreHttpTransport(settings(), { "synthetic-not-reusable" }) { }
        val mapper =
            jacksonObjectMapper()
                .registerModule(JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        val adapter = TransportStoreCoreInventoryAdapter(transport, StoreCoreEnvelopeValidator(), mapper)
        val q = OperationQuadruple("ci-1", "dev-1", "sale-1", "op-seq")
        val catalog = transport.getPath("${StoreCoreCanonicalContract.CANONICAL_PATH}/catalog", q)
        val reserved = adapter.reserve(ReserveInventoryCommand(q, "fixture-v1", emptyList()))
        val got = adapter.getOperation(q)
        val committed = adapter.commit(CommitInventoryCommand(q, "res-fixture-1"))
        val released = adapter.release(ReleaseInventoryCommand(q, "res-fixture-1"))
        val projection = adapter.reconcile(ReconcileQuery(listOf("rcpt-fixture-1", "unknown-rcpt"), clientInstanceId = "ci-1"))
        assertEquals(200, catalog.status)
        assertTrue(catalog.body.contains("fixture-v1"))
        assertEquals(StoreCoreOperationState.RESERVED, reserved.state)
        assertEquals(StoreCoreOperationState.RESERVED, got?.state)
        assertEquals(StoreCoreOperationState.COMMITTED, committed.state)
        assertEquals(StoreCoreOperationState.RELEASED, released.state)
        assertEquals(1, projection.present.size)
        assertEquals("ci-1", projection.present.single().quadruple.clientInstanceId)
        assertEquals("op-seq", projection.present.single().quadruple.operationId)
        assertEquals(listOf("unknown-rcpt"), projection.unknownReceipts)
        assertFalse(projection.unknownAuthorizesRepost())
        assertEquals("Bearer synthetic-not-reusable", lastHeaders["authorization"])
        assertEquals("ci-1", lastHeaders["x-client-instance-id"])
        assertFalse(lastHeaders.containsKey("x-device-id"))
        assertFalse(lastHeaders.containsKey("x-sale-id"))
        assertFalse(lastHeaders.containsKey("x-operation-id"))
        assertEquals(30, StoreCoreContractFixtures.pin().path("rates").path("reserveRps").asInt())
        assertTrue(hits.get() >= 6)
    }

    @Test
    fun disabledCapabilityMakesNoLoopbackCall() {
        startServer()
        val plane = StoreCoreControlPlane()
        assertThrows<BlockedStoreCoreIntegrationException> {
            StoreCoreDispatchGuard.assertCanDispatch(plane.transportSettings(settings()))
        }
        assertEquals(0, hits.get())
    }

    private fun startServer() {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.executor = executor
        http.createContext("/") { exchange ->
            hits.incrementAndGet()
            lastHeaders.clear()
            exchange.requestHeaders.forEach { key, values -> lastHeaders[key.lowercase()] = values.first() }
            val path = exchange.requestURI.path
            val body =
                when {
                    path.endsWith("/catalog") -> StoreCoreContractFixtures.text("catalog")
                    path.endsWith("/reconcile") -> StoreCoreContractFixtures.text("reconcile-present")
                    path.endsWith("/commit") -> StoreCoreContractFixtures.text("committed")
                    path.endsWith("/release") -> StoreCoreContractFixtures.text("released")
                    path.contains("/operations/") -> StoreCoreContractFixtures.text("reserved")
                    else -> StoreCoreContractFixtures.text("reserved")
                }
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        http.start()
        server = http
    }

    private fun settings() =
        StoreCoreTransportSettings(
            enabled = true,
            killSwitch = false,
            capabilityActive = true,
            baseUrl = "http://127.0.0.1:${requireNotNull(server).address.port}",
            tlsRequired = false,
            allowPlainLoopback = true,
            identityRef = "id-ref",
            tokenRef = "tok-ref",
            timeoutMs = 2000,
            maxRetries = 2,
            path = StoreCoreCanonicalContract.CANONICAL_PATH,
            version = StoreCoreCanonicalContract.VERSION,
            digest = StoreCoreCanonicalContract.SHA256,
        )
}
