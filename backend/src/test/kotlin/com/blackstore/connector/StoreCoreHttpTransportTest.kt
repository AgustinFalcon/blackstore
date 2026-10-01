package com.blackstore.connector

import com.blackstore.application.storecore.StoreCoreDispatchGuard
import com.blackstore.application.storecore.StoreCoreRetryPolicy
import com.blackstore.application.storecore.StoreCoreTransportSettings
import com.blackstore.application.storecore.StoreCoreTransportTelemetry
import com.blackstore.domain.exception.BlockedStoreCoreIntegrationException
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.model.StoreCoreCanonicalContract
import com.blackstore.infrastructure.storecore.StoreCoreHttpTransport
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

class StoreCoreHttpTransportTest {

    private var server: HttpServer? = null
    private val executor = Executors.newCachedThreadPool()
    private val posts = AtomicInteger()
    private val gets = AtomicInteger()
    private val lastHeaders = mutableMapOf<String, String>()

    private val quadruple = OperationQuadruple("ci-1", "dev-1", "sale-1", "op-1")

    @AfterEach
    fun stop() {
        server?.stop(0)
        executor.shutdownNow()
    }

    @Test
    fun disabledKillSwitchOrCapabilityMakesNoCall() {
        startServer()
        assertThrows<BlockedStoreCoreIntegrationException> {
            StoreCoreDispatchGuard.assertCanDispatch(settings(enabled = false))
        }
        assertThrows<BlockedStoreCoreIntegrationException> {
            StoreCoreDispatchGuard.assertCanDispatch(settings(killSwitch = true))
        }
        assertThrows<BlockedStoreCoreIntegrationException> {
            StoreCoreDispatchGuard.assertCanDispatch(settings(capabilityActive = false))
        }
        assertThrows<BlockedStoreCoreIntegrationException> {
            StoreCoreHttpTransport(settings(identityRef = "", tokenRef = "tok-ref"), { "x" }).get(quadruple)
        }
        assertEquals(0, posts.get() + gets.get())
    }

    @Test
    fun tlsRequiredRejectsPlaintext() {
        startServer()
        assertThrows<BlockedStoreCoreIntegrationException> {
            StoreCoreDispatchGuard.assertCanDispatch(settings(tlsRequired = true))
        }
        assertEquals(0, posts.get() + gets.get())
    }

    @Test
    fun plaintextNonLoopbackIsRejected() {
        assertThrows<BlockedStoreCoreIntegrationException> {
            StoreCoreDispatchGuard.assertCanDispatch(
                settings(
                    baseUrl = "http://example.invalid",
                    tlsRequired = false,
                    allowPlainLoopback = true,
                ),
            )
        }
    }

    @Test
    fun loopbackGetAttachesBearerAndCanonicalHeaders() {
        startServer()
        val transport =
            StoreCoreHttpTransport(settings(), { ref ->
                if (ref == "tok-ref") "synthetic-not-reusable" else null
            })
        val response = transport.get(quadruple)
        assertEquals(200, response.status)
        assertEquals("Bearer synthetic-not-reusable", lastHeaders["authorization"])
        assertEquals("ci-1", lastHeaders["x-client-instance-id"])
        assertEquals("dev-1", lastHeaders["x-device-id"])
        assertEquals("sale-1", lastHeaders["x-sale-id"])
        assertEquals("op-1", lastHeaders["x-operation-id"])
    }

    @Test
    fun uncertainPostRecoversWithGetAndDoesNotRepost() {
        startServer(dropFirstPost = true)
        val transport = StoreCoreHttpTransport(settings(timeoutMs = 500), { "synthetic-not-reusable" })
        val response = transport.post("${StoreCoreCanonicalContract.CANONICAL_PATH}/reservations", "{}", quadruple)
        assertTrue(response.recoveredViaGet)
        assertEquals(1, posts.get())
        assertTrue(gets.get() >= 1)
        assertEquals(200, response.status)
    }

    @Test
    fun defaultCoroutineWaitHonorsZeroRetryAfter() {
        startServer(rateLimitPosts = 1)
        val transport = StoreCoreHttpTransport(settings(maxRetries = 2), { "synthetic-not-reusable" })
        val response = transport.post("${StoreCoreCanonicalContract.CANONICAL_PATH}/reservations", "{}", quadruple)
        assertEquals(200, response.status)
        assertEquals(1, response.retryCount)
        assertEquals(2, posts.get())
    }

    @Test
    fun completedPostRetryAfterKeepsSameQuadruple() {
        startServer(rateLimitPosts = 1)
        val transport = StoreCoreHttpTransport(settings(maxRetries = 2), { "synthetic-not-reusable" }) { }
        val response = transport.post("${StoreCoreCanonicalContract.CANONICAL_PATH}/reservations", "{}", quadruple)
        assertEquals(200, response.status)
        assertEquals(1, response.retryCount)
        assertEquals(2, posts.get())
        assertEquals("op-1", lastHeaders["x-operation-id"])
    }

    @Test
    fun getHonorsRetryAfterWithinBound() {
        startServer(rateLimitGets = 1)
        val transport = StoreCoreHttpTransport(settings(maxRetries = 2), { "synthetic-not-reusable" }) { }
        val response = transport.get(quadruple)
        assertEquals(200, response.status)
        assertEquals(1, response.retryCount)
        assertEquals(0L, StoreCoreRetryPolicy.waitMillis("0"))
        assertEquals(5000L, StoreCoreRetryPolicy.waitMillis("99"))
        assertFalse(StoreCoreRetryPolicy.shouldRetryPost(attemptCompleted = false, retryable = true))
    }

    @Test
    fun telemetryNeverIncludesBearerOrToken() {
        val line =
            StoreCoreTransportTelemetry.line(
                "GET",
                StoreCoreCanonicalContract.SHA256,
                "HTTP_200",
                0,
                "op-1",
                "id-ref",
            )
        assertFalse(StoreCoreTransportTelemetry.containsSecret(line, "synthetic-not-reusable"))
        assertTrue(line.contains("id-ref"))
        assertTrue(line.contains(StoreCoreCanonicalContract.SHA256.take(8)))
    }

    private fun startServer(dropFirstPost: Boolean = false, rateLimitGets: Int = 0, rateLimitPosts: Int = 0) {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.executor = executor
        var remainingGet429 = rateLimitGets
        var remainingPost429 = rateLimitPosts
        http.createContext("/") { exchange ->
            exchange.requestHeaders.forEach { key, values -> lastHeaders[key.lowercase()] = values.first() }
            val method = exchange.requestMethod
            if (method == "POST") {
                posts.incrementAndGet()
                if (dropFirstPost) {
                    exchange.close()
                    return@createContext
                }
                if (remainingPost429 > 0) {
                    remainingPost429--
                    exchange.responseHeaders.add("Retry-After", "0")
                    exchange.sendResponseHeaders(429, 2)
                    exchange.responseBody.use { it.write("{}".toByteArray()) }
                    return@createContext
                }
            } else {
                gets.incrementAndGet()
                if (remainingGet429 > 0) {
                    remainingGet429--
                    exchange.responseHeaders.add("Retry-After", "0")
                    exchange.sendResponseHeaders(429, 2)
                    exchange.responseBody.use { it.write("{}".toByteArray()) }
                    return@createContext
                }
            }
            val body = """{"ok":true}"""
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        http.start()
        server = http
    }

    private fun settings(
        enabled: Boolean = true,
        killSwitch: Boolean = false,
        capabilityActive: Boolean = true,
        baseUrl: String? = null,
        tlsRequired: Boolean = false,
        allowPlainLoopback: Boolean = true,
        identityRef: String = "id-ref",
        tokenRef: String = "tok-ref",
        timeoutMs: Int = 2000,
        maxRetries: Int = 2,
    ) = StoreCoreTransportSettings(
        enabled = enabled,
        killSwitch = killSwitch,
        capabilityActive = capabilityActive,
        baseUrl = baseUrl ?: "http://127.0.0.1:${requireNotNull(server).address.port}",
        tlsRequired = tlsRequired,
        allowPlainLoopback = allowPlainLoopback,
        identityRef = identityRef,
        tokenRef = tokenRef,
        timeoutMs = timeoutMs,
        maxRetries = maxRetries,
        path = StoreCoreCanonicalContract.CANONICAL_PATH,
        version = StoreCoreCanonicalContract.VERSION,
        digest = StoreCoreCanonicalContract.SHA256,
    )
}
