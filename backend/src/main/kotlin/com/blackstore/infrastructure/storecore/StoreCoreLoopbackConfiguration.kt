package com.blackstore.infrastructure.storecore

import com.blackstore.application.storecore.StoreCoreDispatchGuard
import com.blackstore.application.storecore.StoreCoreEnvelopeValidator
import com.blackstore.application.storecore.StoreCoreIntegrationProperties
import com.blackstore.application.storecore.StoreCoreTransportSettings
import com.blackstore.domain.model.StoreCoreContractRef
import com.blackstore.domain.port.out.storecore.OperationRetirementPort
import com.blackstore.domain.port.out.storecore.CatalogSnapshotStore
import com.blackstore.domain.port.out.storecore.StoreCoreCatalogPort
import com.blackstore.infrastructure.concurrency.DispatcherProvider
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import java.util.Collections

@Configuration
@ConditionalOnProperty(
    name = ["blackstore.storecore.integration.mode"],
    havingValue = "loopback",
)
class StoreCoreLoopbackConfiguration {
    @Bean
    fun storeCoreTransportSettings(properties: StoreCoreIntegrationProperties) =
        properties.toSettings().also(StoreCoreDispatchGuard::assertCanDispatch)

    @Bean
    fun storeCoreHttpTransport(
        settings: StoreCoreTransportSettings,
        dispatchers: DispatcherProvider,
    ): StoreCoreHttpTransport =
        StoreCoreHttpTransport(
            settings,
            resolveToken = { ref ->
                if (ref == StoreCoreIntegrationProperties.LOCAL_LOOPBACK_TOKEN_REF) {
                    StoreCoreIntegrationProperties.LOCAL_LOOPBACK_TOKEN
                } else {
                    null
                }
            },
            dispatchers = dispatchers,
        )

    @Bean
    fun loopbackInventoryPort(
        transport: StoreCoreHttpTransport,
        validator: StoreCoreEnvelopeValidator,
        mapper: ObjectMapper,
        properties: StoreCoreIntegrationProperties,
    ): TransportStoreCoreInventoryAdapter =
        TransportStoreCoreInventoryAdapter(
            transport = transport,
            validator = validator,
            mapper = mapper,
            path = properties.contract.canonicalPath,
            digest = properties.contract.sha256,
        )

    @Bean
    fun loopbackCatalogPort(
        transport: StoreCoreHttpTransport,
        mapper: ObjectMapper,
        properties: StoreCoreIntegrationProperties,
        stores: ObjectProvider<CatalogSnapshotStore>,
        @Value("\${blackstore.catalog-cache.enabled:false}") cacheEnabled: Boolean,
    ): StoreCoreCatalogPort {
        val contract = StoreCoreContractRef(
            properties.contract.canonicalPath,
            properties.contract.version,
            properties.contract.sha256,
        )
        val remote = LoopbackCatalogAdapter(
            transport = transport,
            clientInstanceId = properties.transport.clientInstanceId,
            contract = contract,
            mapper = mapper,
        )
        if (!cacheEnabled) return remote
        val store = stores.ifAvailable ?: error("catalog cache enabled without durable store")
        return DurableCatalogAdapter(remote, store, contract)
    }

    @Bean
    fun loopbackRetirementPort(): OperationRetirementPort = InMemoryOperationRetirement()
}

private class InMemoryOperationRetirement : OperationRetirementPort {
    private val retired = Collections.synchronizedSet(mutableSetOf<String>())

    override fun markRetired(operationId: String) {
        retired.add(operationId)
    }

    override fun isRetired(operationId: String): Boolean = operationId in retired
}
