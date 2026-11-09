package com.blackstore.infrastructure.storecore

import com.blackstore.application.storecore.StoreCoreIntegrationProperties
import com.blackstore.domain.exception.BlockedStoreCoreIntegrationException
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.model.StoreCoreContractRef
import com.blackstore.domain.port.out.storecore.CatalogItem
import com.blackstore.domain.port.out.storecore.CatalogSnapshot
import com.blackstore.domain.port.out.storecore.CatalogSource
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
            val activeNodes=data.path("items").filter { it.path("active").asBoolean(true) }
            fun item(node: JsonNode,legacy: Boolean): CatalogItem? {
                    val rawSku = node.path("sku").asText("")
                    val rawName = node.path("name").asText("")
                    val rawVariant = node.path("variantId").asText("")
                    val sku = rawSku.trim()
                    val name = rawName.trim()
                    val variant = rawVariant.trim()
                    if(if(legacy) sku.isEmpty() || name.isEmpty() || variant.isEmpty() else rawSku.isEmpty() || rawName.isEmpty() || rawVariant.isEmpty()) return null
                    val rawPriceVersion = node.path("priceVersion").asText("").takeIf { it.isNotEmpty() }
                    val priceVersion = rawPriceVersion?.trim()?.ifEmpty { null }
                    val unitPrice =
                        node.path("unitPrice").takeIf { !it.isMissingNode && !it.isNull }?.decimalValue()
                    return CatalogItem(sku, name, variant, priceVersion, unitPrice, rawSku, rawName, rawVariant, rawPriceVersion)
            }
            val items=activeNodes.mapNotNull { item(it,true) }
            val v2Items=activeNodes.mapNotNull { item(it,false) }
            val expired = validUntil != null && !validUntil.isAfter(now)
            return CatalogSnapshot(
                version = version,
                importedAt = importedAt,
                validUntil = validUntil,
                contract = contract,
                stale = expired || items.isEmpty(),
                source = CatalogSource.STORECORE_LIVE,
                items = items,
                v2Items = v2Items,
                v2Stale = expired || v2Items.isEmpty(),
            )
        }

        private fun instantOrNull(node: JsonNode): Instant? {
            val text = node.asText("").trim()
            if (text.isEmpty()) return null
            return runCatching { Instant.parse(text) }.getOrNull()
        }
    }
}
