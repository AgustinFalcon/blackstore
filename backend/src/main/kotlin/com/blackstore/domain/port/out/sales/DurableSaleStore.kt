package com.blackstore.domain.port.out.sales

import com.blackstore.domain.sales.*
import java.time.Duration
import java.util.UUID

interface DurableSaleStore {
 fun findDurable(operationId: String): StoredSale?
 fun listDurable(afterId: Long = 0, limit: Int = 50): List<StoredSale>
 fun claimNext(lease: Duration): ClaimedSaleCommand?
 fun claimCommand(operationId: String, kind: CommandKind, lease: Duration): ClaimedSaleCommand?
 fun applyClaimEvidence(claim: ClaimedSaleCommand, saga: SaleSaga, responseHash: String, remoteState: String,
     receipt: com.blackstore.domain.model.StoreCoreOperationReceipt? = null): AttemptOutcome
 fun reconcileClaimEvidence(claim: ClaimedSaleCommand, receipt: com.blackstore.domain.model.StoreCoreOperationReceipt, reason: RecoveryReason): AttemptOutcome
 fun deferClaim(claim: ClaimedSaleCommand, reason: RecoveryReason, delay: Duration, reconciliation: Boolean = false): Boolean
}
