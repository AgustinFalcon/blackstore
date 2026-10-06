package com.blackstore.connector

import com.blackstore.application.identity.AuthorizeStaffAction
import com.blackstore.application.sales.LocalSaleSagaService
import com.blackstore.domain.cash.*
import com.blackstore.domain.identity.*
import com.blackstore.domain.model.*
import com.blackstore.domain.port.out.storecore.ReserveLineCommand
import com.blackstore.domain.sales.*
import com.blackstore.domain.port.out.sales.SaleRecordStore
import com.blackstore.infrastructure.storecore.FixtureCatalogAdapter
import com.blackstore.infrastructure.counter.InMemoryCounterEntryStore
import com.blackstore.application.counter.CounterApplicationService
import com.blackstore.application.sales.LocalSaleCoordinator
import com.blackstore.domain.port.out.counter.CounterEntryStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.math.BigDecimal

class StaffSaleAuthorizationRegressionTest {
    private val now=Instant.now()
    private val owner=AuthenticatedStaff(StaffUserId(2),"Victim",StaffRole.CASHIER)
    private val attacker=AuthenticatedStaff(StaffUserId(1),"Other",StaffRole.CASHIER)
    private class Ownership : StaffOwnershipQuery {
        var persisted: OwnedCashSession?=null
        override fun cash(id: Long)=OwnedCashSession(id,StaffUserId(id),CashSessionStatus.OPEN)
        override fun sale(operationId: String)=persisted
        override fun sale(identity: OperationQuadruple)=persisted
        override fun payment(paymentId: Long,identity: OperationQuadruple)=persisted
        override fun eligibleCashier(id: StaffUserId)=true
    }
    private val audit=object : SecurityAuditPort { override fun record(event: SecurityAuditEvent,actor: StaffUserId?,target: StaffUserId?)=Unit }
    private fun service(inventory: ScriptedStoreCoreInventoryAdapter,ownership: Ownership,store: SaleRecordStore,ledger: InMemoryCounterEntryStore)=LocalSaleSagaService(
        FixtureCatalogAdapter(StoreCoreCanonicalContract.CANONICAL_PATH,StoreCoreCanonicalContract.VERSION),inventory,inventory,store,
        StoreCoreCanonicalContract.CANONICAL_PATH,StoreCoreCanonicalContract.VERSION,counterEntryStore=ledger,authorization=AuthorizeStaffAction(ownership,audit))
    @Test fun foreignReplayWithOwnSubmittedCashNeverReturnsEvidenceOrRecovers() {
        for(pending in listOf(false,true)) {
            val q=OperationQuadruple("11111111-1111-1111-1111-111111111111","device","sale","replay-$pending")
            val inventory=ScriptedStoreCoreInventoryAdapter(if(pending) ReserveScript.Fault("CONFLICT") else ReserveScript.Receipt(ScriptedStoreCoreInventoryAdapter.durable(q)))
            val ownership=Ownership(); val store=InMemorySaleRecordStore(); val service=service(inventory,ownership,store,InMemoryCounterEntryStore())
            service.beginReserve(owner,q,2,listOf(ReserveLineCommand("variant-1",1,"price-v1")),emptyList(),now,null)
            ownership.persisted=ownership.cash(2)
            val reserveCount=inventory.reserveAttempts.size; val getCount=inventory.getAttempts.size; val events=store.events.toList()
            val denial=assertThrows<StaffSecurityException> { service.beginReserve(attacker,q,1,listOf(ReserveLineCommand("variant-1",1,"price-v1")),emptyList(),now,null) }
            assertEquals(StaffSecurityFailure.NOT_FOUND,denial.failure)
            assertEquals(reserveCount,inventory.reserveAttempts.size); assertEquals(getCount,inventory.getAttempts.size); assertEquals(events,store.events)
            assertThrows<StaffSecurityException> { service.beginReserve(owner,q,1,listOf(ReserveLineCommand("variant-1",1,"price-v1")),emptyList(),now,null) }
        }
    }
    @Test fun commitAndReleaseCarryTrustedActorAndOverrideReasonToDurableCommand() {
        for(event in listOf(SaleStaffCommandEvent.COMMIT_REQUESTED,SaleStaffCommandEvent.RELEASE_REQUESTED)) {
            val q=OperationQuadruple("11111111-1111-1111-1111-111111111111","device","sale",event.name)
            val inventory=ScriptedStoreCoreInventoryAdapter(ReserveScript.Receipt(ScriptedStoreCoreInventoryAdapter.durable(q)))
            val ownership=Ownership(); var recorded: SaleSaga?=null
            val store=object : SaleRecordStore by InMemorySaleRecordStore() {
                override fun recordCommitPending(saga: SaleSaga) { recorded=saga }
                override fun recordReleasePending(saga: SaleSaga) { recorded=saga }
            }
            val ledger=InMemoryCounterEntryStore(); val service=service(inventory,ownership,store,ledger)
            service.beginReserve(owner,q,2,listOf(ReserveLineCommand("variant-1",1,"price-v1")),listOf(TicketLine("SKU-1","Cafe",1,BigDecimal("18"),BigDecimal.ZERO)),now,null)
            ownership.persisted=ownership.cash(2)
            if(event==SaleStaffCommandEvent.COMMIT_REQUESTED) ledger.savePayment(PaymentBook().capture(1,PaymentMethod.CASH,BigDecimal("18"),BigDecimal.ZERO),q)
            val supervisor=attacker.copy(role=StaffRole.SUPERVISOR)
            if(event==SaleStaffCommandEvent.COMMIT_REQUESTED) service.commit(supervisor,q.operationId,"supervisor correction") else service.release(supervisor,q.operationId,"supervisor correction")
            assertEquals(SaleStaffCommandAudit(event,supervisor.id,"supervisor correction"),recorded!!.staffCommandAudit)
        }
        assertThrows<IllegalArgumentException> { SaleStaffCommandAudit(SaleStaffCommandEvent.UNKNOWN,owner.id,null) }
        assertEquals(SaleStaffCommandEvent.COMMIT_REQUESTED,SaleStaffCommandEvent.fromWire("COMMIT_REQUESTED"))
        assertEquals(SaleStaffCommandEvent.UNKNOWN,SaleStaffCommandEvent.fromWire("forged event"))
        assertEquals(SaleStaffCommandEvent.UNKNOWN,SaleStaffCommandEvent.fromWire(null))
    }

    @Test fun supervisorPaymentCarriesOverrideReasonToDurableStore() {
        val q=OperationQuadruple("11111111-1111-1111-1111-111111111111","device","sale","payment-override")
        val inventory=ScriptedStoreCoreInventoryAdapter(ReserveScript.Receipt(ScriptedStoreCoreInventoryAdapter.durable(q)))
        val ownership=Ownership(); val saleStore=InMemorySaleRecordStore(); val ledger=InMemoryCounterEntryStore()
        val sales=service(inventory,ownership,saleStore,ledger)
        sales.beginReserve(owner,q,2,listOf(ReserveLineCommand("variant-1",1,"price-v1")),listOf(TicketLine("SKU-1","Cafe",1,BigDecimal("18"),BigDecimal.ZERO)),now,null)
        ownership.persisted=ownership.cash(2)
        var persisted: PaymentRecord?=null
        val recording=object : CounterEntryStore by ledger {
            override fun savePayment(payment: PaymentRecord, identity: OperationQuadruple): PaymentRecord {
                persisted=payment
                return ledger.savePayment(payment,identity)
            }
        }
        val supervisor=attacker.copy(role=StaffRole.SUPERVISOR)
        CounterApplicationService(recording,sales,LocalSaleCoordinator.local,AuthorizeStaffAction(ownership,audit))
            .capture(supervisor,q,PaymentMethod.CASH,BigDecimal("18"),BigDecimal.ZERO,"supervisor correction")

        assertEquals(supervisor.id.value,persisted!!.actorId)
        assertEquals("supervisor correction",persisted!!.reason)
    }
}
