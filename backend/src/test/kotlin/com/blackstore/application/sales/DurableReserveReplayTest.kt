package com.blackstore.application.sales

import com.blackstore.domain.model.*
import com.blackstore.domain.port.out.sales.*
import com.blackstore.domain.port.out.storecore.*
import com.blackstore.domain.sales.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class DurableReserveReplayTest {
    @Test fun identicalSubmittedRequestReopensStoredCanonicalSaleWithoutCatalogOrHttp() {
        val identity=OperationQuadruple(UUID.randomUUID().toString(),"device","sale",UUID.randomUUID().toString())
        val submitted=listOf(ReserveLineCommand("wire-variant",1,"wire-price"))
        val ticket=listOf(TicketLine("sku","name",1,BigDecimal("10"),BigDecimal.ZERO))
        val hash=ReserveRequestFingerprint.hash(identity,1,submitted,ticket,null)
        val command=OutboxCommand(identity,StoreCoreOperationKind.RESERVE,StoreCoreCanonicalContract.CANONICAL_PATH,
            StoreCoreCanonicalContract.VERSION,StoreCoreCanonicalContract.SHA256,hash,
            payload=CanonicalCommandPayload.Reserve("historical-catalog",listOf(CanonicalReserveLine("resolved-variant",1,"resolved-price"))))
        val evidence=RemoteEvidence("ref","receipt",command.contractVersion,command.openapiDigest,listOf("resolved-price"),Instant.now().plusSeconds(600))
        val stored=SaleSaga(identity,1,SaleStatus.RESERVED,evidence,outbox=listOf(command),
            lines=listOf(ticket.single().copy(originalUnitPrice=BigDecimal("10.00"),discountAmount=BigDecimal("0.00"))),createdBy=1)
        val records=mock(SaleRecordStore::class.java,withSettings().extraInterfaces(DurableSaleStore::class.java))
        `when`((records as DurableSaleStore).findDurable(identity.operationId)).thenReturn(StoredSale(stored,0,DurableSaleState.RESERVED,1))
        val catalog=mock(StoreCoreCatalogPort::class.java);val inventory=mock(StoreCoreInventoryPort::class.java)
        val retirement=mock(OperationRetirementPort::class.java)
        val service=LocalSaleSagaService(catalog,inventory,retirement,records,command.canonicalPath,command.contractVersion,persistenceEnabled=true)
        assertEquals(stored,service.beginReserve(identity,1,submitted,ticket,Instant.now(),1))
        assertThrows<IllegalArgumentException> { service.beginReserve(identity,1,submitted,listOf(ticket.single().copy(quantity=2)),Instant.now(),1) }
        verifyNoInteractions(catalog,inventory,retirement)
    }
}
