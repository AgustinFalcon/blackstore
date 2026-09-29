package com.blackstore.connector

import com.blackstore.application.sales.LocalSaleSagaService
import com.blackstore.application.storecore.StoreCoreEnvelopeValidator
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.port.out.sales.RecordedSale
import com.blackstore.domain.port.out.sales.SaleRecordStore
import com.blackstore.domain.port.out.storecore.ReserveLineCommand
import com.blackstore.domain.sales.SaleSaga
import com.blackstore.domain.sales.SaleStatus
import com.blackstore.infrastructure.sales.NoOpSaleRecordStore
import com.blackstore.infrastructure.storecore.FixtureCatalogAdapter
import com.blackstore.infrastructure.storecore.FixtureStoreCoreInventoryAdapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class LocalSaleSagaRestartReadTest {
    private val now = Instant.parse("2026-09-22T15:00:00Z")

    @Test
    fun storedFindsCommittedSnapshotWhenTheProcessMapIsEmpty() {
        val operationId = "22222222-2222-2222-2222-222222222222"
        val recorded =
            RecordedSale(
                clientInstanceId = "11111111-1111-1111-1111-111111111111",
                deviceId = "terminal-1",
                saleId = "sale-restart",
                operationId = operationId,
                cashSessionId = 4,
                status = SaleStatus.COMMITTED,
                receipt = "rcpt-committed",
                reservationRef = "res-committed",
                contractVersion = "1.0.0-draft",
                openapiDigest = "a".repeat(64),
                acceptedPriceVersions = listOf("price-v1"),
            )
        val service = service(SnapshotSaleRecordStore(recorded))

        val found = service.stored(operationId)

        assertEquals(SaleStatus.COMMITTED, found?.status)
        assertEquals("rcpt-committed", found?.evidence?.receipt)
        assertEquals("res-committed", found?.evidence?.reservationRef)
        assertEquals(operationId, found?.quadruple?.operationId)
        assertEquals("11111111-1111-1111-1111-111111111111", found?.quadruple?.clientInstanceId)
        assertEquals("terminal-1", found?.quadruple?.deviceId)
        assertEquals("sale-restart", found?.quadruple?.saleId)
        assertEquals(4, found?.cashSessionId)
        assertTrue(found?.outbox?.isEmpty() == true)
        assertNull(service.stored("33333333-3333-3333-3333-333333333333"))
    }

    @Test
    fun storedPrefersTheProcessMapAndIgnoresAnEmptyPort() {
        val store = ReadCountingSaleRecordStore()
        val service = service(store)
        service.beginReserve(
            OperationQuadruple("11111111-1111-1111-1111-111111111111", "terminal-1", "sale-1", "op-mem"),
            cashSessionId = 1,
            lines = listOf(ReserveLineCommand("variant-1", 1, "price-v1")),
            now = now,
        )

        val found = service.stored("op-mem")

        assertEquals(SaleStatus.RESERVED, found?.status)
        assertEquals("rcpt-op-mem", found?.evidence?.receipt)
        assertEquals(0, store.reads)
        assertNull(service(NoOpSaleRecordStore()).stored("missing-after-restart"))
    }

    @Test
    fun unknownPersistedStatusIsRejected() {
        assertEquals(SaleStatus.COMMITTED, SaleStatus.fromPersisted("COMMITTED"))
        assertThrows<IllegalArgumentException> { SaleStatus.fromPersisted("PAID") }
    }

    private fun service(store: SaleRecordStore): LocalSaleSagaService {
        val catalog = FixtureCatalogAdapter("/blackstore-integration/v1", "1.0.0-draft")
        val inventory =
            FixtureStoreCoreInventoryAdapter(
                StoreCoreEnvelopeValidator(),
                "/blackstore-integration/v1",
                "1.0.0-draft",
            )
        return LocalSaleSagaService(
            catalogPort = catalog,
            inventoryPort = inventory,
            retirementPort = inventory,
            saleRecordStore = store,
            canonicalPath = "/blackstore-integration/v1",
            contractVersion = "1.0.0-draft",
        )
    }
}

private class SnapshotSaleRecordStore(
    private val recorded: RecordedSale,
) : SaleRecordStore {
    override fun findRecorded(operationId: String): RecordedSale? =
        recorded.takeIf { it.operationId == operationId }

    override fun recordReserved(saga: SaleSaga) = Unit

    override fun recordCommitPending(saga: SaleSaga) = Unit

    override fun recordCommitted(saga: SaleSaga) = Unit

    override fun recordReleasePending(saga: SaleSaga) = Unit

    override fun recordReleased(saga: SaleSaga) = Unit
}

private class ReadCountingSaleRecordStore : SaleRecordStore {
    var reads: Int = 0

    override fun findRecorded(operationId: String): RecordedSale? {
        reads += 1
        return null
    }

    override fun recordReserved(saga: SaleSaga) = Unit

    override fun recordCommitPending(saga: SaleSaga) = Unit

    override fun recordCommitted(saga: SaleSaga) = Unit

    override fun recordReleasePending(saga: SaleSaga) = Unit

    override fun recordReleased(saga: SaleSaga) = Unit
}
