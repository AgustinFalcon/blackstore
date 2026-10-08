package com.blackstore.application.sales

import com.blackstore.domain.accounting.AccountingRuntimeState
import com.blackstore.domain.cash.*
import com.blackstore.domain.identity.*
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.sales.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class SaleAdmissionStepsTest {
    private val cash=OwnedCashSession(1,StaffUserId(1),CashSessionStatus.CLOSED)
    private fun actor(id: Long,role: StaffRole)=AuthenticatedStaff(StaffUserId(id),"staff",role)
    @Test fun authorityChecksCurrentKindOwnershipAndReadDoesNotRequireOpenOrReason() {
        val step=SaleReceiptAuthorityPolicy()
        for(kind in listOf(SaleCommandKind.Reserve,SaleCommandKind.Commit,SaleCommandKind.Release)) {
            assertNull(step.failure(actor(1,StaffRole.CASHIER),kind,cash,null,false))
            assertEquals(SaleCommandFailure.NotVisible,step.failure(actor(2,StaffRole.CASHIER),kind,cash,null,false))
            assertNull(step.failure(actor(2,StaffRole.SUPERVISOR),kind,cash,null,false))
            assertEquals(SaleCommandFailure.Validation,step.failure(actor(2,StaffRole.OWNER),kind,cash,null,true))
            assertNull(step.failure(actor(2,StaffRole.OWNER),kind,cash,"override",true))
            assertEquals(SaleCommandFailure.Forbidden,step.failure(actor(1,StaffRole.AUDITOR),kind,cash,null,false))
        }
        assertEquals(SaleCommandFailure.Forbidden,step.failure(actor(1,StaffRole.UNKNOWN),SaleCommandKind.Commit,cash,null,true))
    }
    @Test fun lifecycleOnlyAdmitsNewActiveOpenCommands() {
        val step=SaleLifecycleAdmissionStep()
        assertNull(step.failure(AccountingRuntimeState.Active,true))
        assertEquals(SaleCommandFailure.Closed,step.failure(AccountingRuntimeState.Active,false))
        assertEquals(SaleCommandFailure.Paused,step.failure(AccountingRuntimeState.Paused,true))
        assertEquals(SaleCommandFailure.NotActivated,step.failure(AccountingRuntimeState.PreActivation,true))
        assertEquals(SaleCommandFailure.Unavailable,step.failure(AccountingRuntimeState.Unknown,true))
    }
    @Test fun replayChecksIdentityKindAndCanonicalOriginalActorHash() {
        val id=UUID.randomUUID();val q=OperationQuadruple(UUID.randomUUID().toString(),"device","sale",UUID.randomUUID().toString())
        val command=SaleCommand.Commit(id,q,null);val hash=SaleCommandFingerprint().hash(1,1,command)
        val receipt=SaleCommandAdmissionReceipt(id,SaleCommandKind.Commit,hash,1,1,q,1,1,Instant.EPOCH)
        val step=SaleReceiptReplayPolicy()
        assertNull(step.failure(command,receipt,hash))
        assertEquals(SaleCommandFailure.PayloadMismatch,step.failure(command,receipt,"b".repeat(64)))
        assertEquals(SaleCommandFailure.PayloadMismatch,step.failure(SaleCommand.Release(id,q,null),receipt,hash))
        assertEquals(SaleCommandFailure.PayloadMismatch,step.failure(command.copy(identity=q.copy(deviceId="other")),receipt,hash))
    }
}
