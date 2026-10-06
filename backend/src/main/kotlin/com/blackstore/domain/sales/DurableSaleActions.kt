package com.blackstore.domain.sales

import com.blackstore.domain.model.StoreCoreOperationKind

enum class SaleAllowedAction { CAPTURE_PAYMENT, COMMIT, RELEASE, REVERSE_PAYMENT }

class DurableSaleActions {
    fun allowed(sale: StoredSale, ledger: OperationLedger, cashOpen: Boolean): Set<SaleAllowedAction> {
        if (!cashOpen || sale.state in setOf(DurableSaleState.UNKNOWN, DurableSaleState.LEGACY_INCOMPLETE,
                DurableSaleState.RECONCILIATION_REQUIRED) || sale.saga.retired || sale.saga.blockSameOperationRepost) return emptySet()
        val policy = PaymentTransitionPolicy()
        val snapshot = policy.snapshot(sale.saga, ledger)
        if (!snapshot.evidenceValid || snapshot.paymentCoverage == PaymentCoverage.InvalidUnknown) return emptySet()
        return buildSet {
            if (sale.saga.status in setOf(SaleStatus.RESERVED, SaleStatus.PAYMENT_CAPTURED) &&
                snapshot.pendingAmount?.signum() == 1) add(SaleAllowedAction.CAPTURE_PAYMENT)
            if (policy.terminal(sale.saga, ledger, StoreCoreOperationKind.COMMIT) !is TransitionDecision.Denied &&
                sale.saga.status !in setOf(SaleStatus.COMMITTED, SaleStatus.RELEASED)) add(SaleAllowedAction.COMMIT)
            if (policy.terminal(sale.saga, ledger, StoreCoreOperationKind.RELEASE) !is TransitionDecision.Denied &&
                sale.saga.status !in setOf(SaleStatus.COMMITTED, SaleStatus.RELEASED)) add(SaleAllowedAction.RELEASE)
            if ((ledger as? OperationLedger.Known)?.entries?.any { policy.reverse(sale.saga, ledger, it.paymentId) == TransitionDecision.NewCommand } == true)
                add(SaleAllowedAction.REVERSE_PAYMENT)
        }
    }
}
