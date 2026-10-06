package com.blackstore.presentation.controller

import com.blackstore.application.sales.DurableSaleView
import com.blackstore.application.sales.LocalSaleSagaService
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.sales.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.math.BigDecimal
import java.util.UUID

class DurableSaleActorContractTest {
    private val controller = SaleController(mock(LocalSaleSagaService::class.java))
    private fun view(actor: Long?): DurableSaleView {
        val identity = OperationQuadruple(UUID.randomUUID().toString(), "counter", UUID.randomUUID().toString(), UUID.randomUUID().toString())
        val evidence = RemoteEvidence("reservation", "receipt", "contract-v1", "a".repeat(64), listOf("price-v1"), null)
        val sale = StoredSale(SaleSaga(identity, 1, SaleStatus.RESERVED, evidence = evidence, createdBy = actor), 1, DurableSaleState.RESERVED, 1)
        return DurableSaleView(sale, 7, PaymentSnapshot(BigDecimal.TEN, BigDecimal.ZERO, PaymentCoverage.Paid, true, true),
            emptyList(), setOf(SaleAllowedAction.COMMIT))
    }

    @Test fun historicalActorIsSerializedSeparatelyFromCashier() {
        val response = controller.durableResponse(view(9))
        val json = jacksonObjectMapper().valueToTree<com.fasterxml.jackson.databind.JsonNode>(response)
        assertEquals(9, json.path("createdBy").asLong())
        assertEquals(7, json.path("cashierId").asLong())
        assertFalse(response.blocked)
        assertEquals(setOf(SaleAllowedAction.COMMIT), response.allowedActions)
    }

    @Test fun missingOrInvalidHistoricalActorRemainsUnknownAndReadOnly() {
        for (actor in listOf(null, 0L, -1L)) {
            val response = controller.durableResponse(view(actor))
            assertNull(response.createdBy)
            assertEquals(7, response.cashierId)
            assertTrue(response.blocked)
            assertTrue(response.allowedActions.isEmpty())
        }
    }
}
