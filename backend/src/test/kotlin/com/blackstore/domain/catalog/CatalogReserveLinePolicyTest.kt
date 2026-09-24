package com.blackstore.domain.catalog

import com.blackstore.application.sales.LocalSaleSagaService
import com.blackstore.domain.exception.ForbiddenOperationException
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.model.StoreCoreContractRef
import com.blackstore.domain.model.StoreCoreOperationKind
import com.blackstore.domain.model.StoreCoreOperationReceipt
import com.blackstore.domain.model.StoreCoreOperationState
import com.blackstore.domain.port.out.storecore.CatalogItem
import com.blackstore.domain.port.out.storecore.CatalogSnapshot
import com.blackstore.domain.port.out.storecore.CommitInventoryCommand
import com.blackstore.domain.port.out.storecore.OperationRetirementPort
import com.blackstore.domain.port.out.storecore.ReleaseInventoryCommand
import com.blackstore.domain.port.out.storecore.ReserveInventoryCommand
import com.blackstore.domain.port.out.storecore.ReserveLineCommand
import com.blackstore.domain.port.out.storecore.StoreCoreCatalogPort
import com.blackstore.domain.port.out.storecore.StoreCoreInventoryPort
import com.blackstore.domain.sales.SaleStatus
import com.blackstore.domain.sales.TicketLine
import com.blackstore.infrastructure.sales.NoOpSaleRecordStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Instant

class CatalogReserveLinePolicyTest {

    private val now = Instant.parse("2026-09-24T12:00:00Z")

    @Test
    fun skuOnTheProjectionReplacesAFixedVariantAndPriceVersion() {
        val policy = CatalogReserveLinePolicy()
        val resolved =
            policy.resolve(
                snapshot(),
                listOf(ReserveLineCommand("variant-1", 1, "price-v1")),
                listOf(ticket("SKU-9")),
            )
        assertEquals("9", resolved.single().variantId)
        assertEquals("pv-9", resolved.single().expectedPriceVersion)
    }

    @Test
    fun fixtureRowWithoutPriceVersionKeepsTheCashierVersion() {
        val policy = CatalogReserveLinePolicy()
        val resolved =
            policy.resolve(
                snapshot(priceVersion = null, variantId = "variant-1", sku = "SKU-1"),
                listOf(ReserveLineCommand("variant-1", 1, "price-v1")),
                emptyList(),
            )
        assertEquals("variant-1", resolved.single().variantId)
        assertEquals("price-v1", resolved.single().expectedPriceVersion)
    }

    @Test
    fun unknownSkuBlocksTheReserve() {
        val policy = CatalogReserveLinePolicy()
        assertThrows<ForbiddenOperationException> {
            policy.resolve(
                snapshot(),
                listOf(ReserveLineCommand("variant-1", 1, "price-v1")),
                listOf(ticket("SKU-MISSING")),
            )
        }
    }

    @Test
    fun sagaSendsTheProjectionVariantNotTheFixedOne() {
        val inventory = RecordingInventory()
        val service =
            LocalSaleSagaService(
                catalogPort = FixedCatalog(snapshot()),
                inventoryPort = inventory,
                retirementPort = inventory,
                saleRecordStore = NoOpSaleRecordStore(),
                canonicalPath = "/blackstore-integration/v1",
                contractVersion = "1.0.0-draft",
            )
        val reserved =
            service.beginReserve(
                quadruple = OperationQuadruple("11111111-1111-1111-1111-111111111111", "terminal-1", "sale-9", "op-9"),
                cashSessionId = 1,
                lines = listOf(ReserveLineCommand("variant-1", 2, "price-v1")),
                ticketLines = listOf(ticket("SKU-9")),
                now = now,
            )
        assertEquals(SaleStatus.RESERVED, reserved.status)
        val sent = inventory.commands.single().lines.single()
        assertEquals("9", sent.variantId)
        assertEquals("pv-9", sent.expectedPriceVersion)
        assertEquals(2, sent.quantity)
    }

    private fun ticket(sku: String) =
        TicketLine(
            sku = sku,
            productName = "Te",
            quantity = 1,
            originalUnitPrice = BigDecimal("10.50"),
            discountAmount = BigDecimal.ZERO,
        )

    private fun snapshot(
        sku: String = "SKU-9",
        variantId: String = "9",
        priceVersion: String? = "pv-9",
    ) = CatalogSnapshot(
        version = "loopback-v1",
        importedAt = now.minusSeconds(30),
        validUntil = now.plusSeconds(3600),
        contract = StoreCoreContractRef("/blackstore-integration/v1", "1.0.0-draft"),
        stale = false,
        items = listOf(CatalogItem(sku = sku, name = "Te", variantId = variantId, priceVersion = priceVersion)),
    )

    private class FixedCatalog(
        private val snapshot: CatalogSnapshot,
    ) : StoreCoreCatalogPort {
        override fun currentSnapshot(): CatalogSnapshot = snapshot
    }

    private class RecordingInventory : StoreCoreInventoryPort, OperationRetirementPort {
        val commands = mutableListOf<ReserveInventoryCommand>()

        override fun reserve(command: ReserveInventoryCommand): StoreCoreOperationReceipt {
            commands += command
            return StoreCoreOperationReceipt(
                quadruple = command.quadruple,
                kind = StoreCoreOperationKind.RESERVE,
                state = StoreCoreOperationState.RESERVED,
                reservationRef = "res-${command.quadruple.operationId}",
                receipt = "rcpt-${command.quadruple.operationId}",
                contract = StoreCoreContractRef("/blackstore-integration/v1", "1.0.0-draft", "a".repeat(64)),
                acceptedPriceVersions = listOf(command.lines.single().expectedPriceVersion),
                expiresAt = Instant.parse("2026-09-25T00:00:00Z"),
            )
        }

        override fun commit(command: CommitInventoryCommand): StoreCoreOperationReceipt = error("not used")

        override fun release(command: ReleaseInventoryCommand): StoreCoreOperationReceipt = error("not used")

        override fun getOperation(quadruple: OperationQuadruple): StoreCoreOperationReceipt? = null

        override fun markRetired(operationId: String) = Unit

        override fun isRetired(operationId: String): Boolean = false
    }
}
