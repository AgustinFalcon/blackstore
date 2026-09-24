package com.blackstore.infrastructure.storecore

import com.blackstore.application.storecore.StoreCoreDispatchGuard
import com.blackstore.application.storecore.StoreCoreEnvelopeValidator
import com.blackstore.application.storecore.StoreCoreIntegrationProperties
import com.blackstore.application.storecore.StoreCoreTransportSettings
import com.blackstore.domain.model.StoreCoreContractRef
import com.blackstore.domain.port.out.storecore.OperationRetirementPort
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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
    fun storeCoreHttpTransport(settings: StoreCoreTransportSettings): StoreCoreHttpTransport =
        StoreCoreHttpTransport(settings, resolveToken = { ref ->
            if (ref == StoreCoreIntegrationProperties.LOCAL_LOOPBACK_TOKEN_REF) {
                StoreCoreIntegrationProperties.LOCAL_LOOPBACK_TOKEN
            } else {
                null
            }
        })

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
    ): LoopbackCatalogAdapter =
        LoopbackCatalogAdapter(
            transport = transport,
            clientInstanceId = properties.transport.clientInstanceId,
            contract =
                StoreCoreContractRef(
                    properties.contract.canonicalPath,
                    properties.contract.version,
                    properties.contract.sha256,
                ),
            mapper = mapper,
        )

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
