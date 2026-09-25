package com.blackstore.infrastructure.storecore

import com.blackstore.application.storecore.StoreCoreIntegrationProperties
import com.blackstore.domain.exception.BlockedStoreCoreIntegrationException
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.model.StoreCoreContractRef
import com.blackstore.domain.port.out.storecore.CatalogItem
import com.blackstore.domain.port.out.storecore.CatalogSnapshot
import com.blackstore.domain.port.out.storecore.StoreCoreCatalogPort
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

/**
 * Reads StoreCore `/catalog` over the guarded loopback transport.
 * A non-200 or a transport block becomes an unavailable snapshot.
 */
class LoopbackCatalogAdapter(
    private val transport: StoreCoreHttpTransport,
    private val clientInstanceId: String,
    private val contract: StoreCoreContractRef,
    private val mapper: ObjectMapper,
    private val clock: () -> Instant = Instant::now,
) : StoreCoreCatalogPort {

    override fun currentSnapshot(): CatalogSnapshot? {
        val response =
            try {
                transport.getPath(
                    "${contract.canonicalPath}/catalog",
                    OperationQuadruple(
                        clientInstanceId = clientInstanceId,
                        deviceId = "loopback-catalog",
                        saleId = "catalog-read",
                        operationId = UUID.randomUUID().toString(),
                    ),
                )
            } catch (_: BlockedStoreCoreIntegrationException) {
                return null
            } catch (ex: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            } catch (_: Exception) {
                return null
            }
        if (response.status != 200 || response.body.isBlank()) return null
        return parse(mapper.readTree(response.body), contract, clock())
    }

    companion object {
        fun parse(root: JsonNode, contract: StoreCoreContractRef, now: Instant): CatalogSnapshot? {
            if (root.path("code").asInt() != 200) return null
            val data = root.path("data")
            if (data.isMissingNode || data.isNull) return null
            val version = data.path("catalogVersion").asText("").trim()
            if (version.isEmpty()) return null
            val importedAt = instantOrNull(data.path("generatedAt")) ?: now
            val validUntil = instantOrNull(data.path("validUntil"))
            val items =
                data.path("items").mapNotNull { node ->
                    if (!node.path("active").asBoolean(true)) return@mapNotNull null
                    val sku = node.path("sku").asText("").trim()
                    val name = node.path("name").asText("").trim()
                    val variant = node.path("variantId").asText("").trim()
                    if (sku.isEmpty() || name.isEmpty() || variant.isEmpty()) return@mapNotNull null
                    val priceVersion = node.path("priceVersion").asText("").trim().ifEmpty { null }
                    val unitPrice =
                        node.path("unitPrice").takeIf { !it.isMissingNode && !it.isNull }?.decimalValue()
                    CatalogItem(sku, name, variant, priceVersion, unitPrice)
                }
            val stale = validUntil != null && !validUntil.isAfter(now)
            return CatalogSnapshot(
                version = version,
                importedAt = importedAt,
                validUntil = validUntil,
                contract = contract,
                stale = stale || items.isEmpty(),
                items = items,
            )
        }

        private fun instantOrNull(node: JsonNode): Instant? {
            val text = node.asText("").trim()
            if (text.isEmpty()) return null
            return runCatching { Instant.parse(text) }.getOrNull()
        }
    }
}
