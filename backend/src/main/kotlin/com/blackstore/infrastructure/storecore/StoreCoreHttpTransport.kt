package com.blackstore.infrastructure.storecore

import com.blackstore.application.storecore.StoreCoreDispatchGuard
import com.blackstore.application.storecore.StoreCoreRetryPolicy
import com.blackstore.application.storecore.StoreCoreTransportSettings
import com.blackstore.application.storecore.StoreCoreTransportTelemetry
import com.blackstore.domain.exception.BlockedStoreCoreIntegrationException
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.infrastructure.concurrency.DispatcherProvider
import com.blackstore.infrastructure.concurrency.ServerDispatcherProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class StoreCoreTransportResponse(
    val status: Int,
    val body: String,
    val recoveredViaGet: Boolean,
    val retryCount: Int,
)

/**
 * Local/Testcontainers HTTP only. Capability stays fail-closed unless tests construct this.
 * Token values are resolved at call time and never logged.
 *
 * Retry waits and blocking HTTP run on the injected [DispatcherProvider.io] (issues #10/#12).
 * This is not a Mercado Libre outbox dispatcher and does not open a live companion.
 */
class StoreCoreHttpTransport(
    private val settings: StoreCoreTransportSettings,
    private val resolveToken: (String) -> String?,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(settings.timeoutMs.toLong())).build(),
    private val dispatchers: DispatcherProvider = ServerDispatcherProvider(),
    private val sleeper: (Long) -> Unit = { ms -> if (ms > 0) runBlocking(dispatchers.io) { delay(ms) } },
) {
    private val log = LoggerFactory.getLogger(StoreCoreHttpTransport::class.java)

    fun get(quadruple: OperationQuadruple): StoreCoreTransportResponse {
        StoreCoreDispatchGuard.assertCanDispatch(settings)
        return execute("GET", operationPath(quadruple), null, quadruple, recoveredViaGet = false)
    }

    fun getPath(path: String, quadruple: OperationQuadruple): StoreCoreTransportResponse {
        StoreCoreDispatchGuard.assertCanDispatch(settings)
        return execute("GET", path, null, quadruple, recoveredViaGet = false)
    }

    fun postClientInstance(path: String, body: String, clientInstanceId: String): StoreCoreTransportResponse {
        StoreCoreDispatchGuard.assertCanDispatch(settings)
        val token =
            resolveToken(settings.tokenRef)
                ?: throw BlockedStoreCoreIntegrationException("STORECORE_TOKEN_UNRESOLVED")
        val request =
            HttpRequest.newBuilder(URI.create(settings.baseUrl.trimEnd('/') + path))
                .timeout(Duration.ofMillis(settings.timeoutMs.toLong()))
                .header("Authorization", "Bearer $token")
                .header("X-Client-Instance-Id", clientInstanceId)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .build()
        val response = send(request)
        log.info(StoreCoreTransportTelemetry.line("RECONCILE", settings.digest, "HTTP_${response.statusCode()}", 0, clientInstanceId, settings.identityRef))
        return StoreCoreTransportResponse(response.statusCode(), response.body(), false, 0)
    }

    fun post(path: String, body: String, quadruple: OperationQuadruple): StoreCoreTransportResponse {
        StoreCoreDispatchGuard.assertCanDispatch(settings)
        return try {
            execute("POST", path, body, quadruple, recoveredViaGet = false)
        } catch (ex: Exception) {
            log.info(StoreCoreTransportTelemetry.line("POST_UNCERTAIN", settings.digest, "GET_RECOVERY", 0, quadruple.operationId, settings.identityRef))
            execute("GET", operationPath(quadruple), null, quadruple, recoveredViaGet = true)
        }
    }

    private fun execute(
        method: String,
        path: String,
        body: String?,
        quadruple: OperationQuadruple,
        recoveredViaGet: Boolean,
    ): StoreCoreTransportResponse {
        val token =
            resolveToken(settings.tokenRef)
                ?: throw BlockedStoreCoreIntegrationException("STORECORE_TOKEN_UNRESOLVED")
        var attempt = 0
        while (true) {
            val request = request(method, path, body, quadruple, token)
            val response = send(request)
            val retryAfter = response.headers().firstValue("Retry-After").orElse(null)
            val retryable = response.statusCode() == 429
            if (retryable && method == "GET" && StoreCoreRetryPolicy.shouldRetryGet(attempt, settings.maxRetries, true)) {
                attempt++
                sleeper(StoreCoreRetryPolicy.waitMillis(retryAfter))
                continue
            }
            if (retryable && method == "POST" && StoreCoreRetryPolicy.shouldRetryPost(true, true) && attempt < settings.maxRetries) {
                attempt++
                sleeper(StoreCoreRetryPolicy.waitMillis(retryAfter))
                continue
            }
            log.info(StoreCoreTransportTelemetry.line(method, settings.digest, "HTTP_${response.statusCode()}", attempt, quadruple.operationId, settings.identityRef))
            return StoreCoreTransportResponse(response.statusCode(), response.body(), recoveredViaGet, attempt)
        }
    }

    private fun send(request: HttpRequest): HttpResponse<String> =
        runBlocking(dispatchers.io) { client.send(request, HttpResponse.BodyHandlers.ofString()) }

    private fun request(
        method: String,
        path: String,
        body: String?,
        quadruple: OperationQuadruple,
        token: String,
    ): HttpRequest {
        val builder =
            HttpRequest.newBuilder(URI.create(settings.baseUrl.trimEnd('/') + path))
                .timeout(Duration.ofMillis(settings.timeoutMs.toLong()))
                .header("Authorization", "Bearer $token")
                .header("X-Client-Instance-Id", quadruple.clientInstanceId)
                .header("X-Device-Id", quadruple.deviceId)
                .header("X-Sale-Id", quadruple.saleId)
                .header("X-Operation-Id", quadruple.operationId)
        return if (method == "GET") {
            builder.GET().build()
        } else {
            builder.POST(HttpRequest.BodyPublishers.ofString(body ?: "")).header("Content-Type", "application/json").build()
        }
    }

    private fun operationPath(quadruple: OperationQuadruple) =
        "${settings.path}/operations/${quadruple.operationId}"

}
