package com.blackstore.domain.accounting

import com.blackstore.domain.model.StoreCoreOperationKind
import com.blackstore.domain.model.StoreCoreOperationReceipt
import com.blackstore.domain.model.StoreCoreOperationState
import com.blackstore.domain.sales.CanonicalCommandPayload
import com.blackstore.domain.sales.OutboxCommand
import com.blackstore.domain.sales.SaleSaga
import com.blackstore.domain.sales.SaleStatus

/** Reservation and terminal receipts are distinct evidence; only an applied terminal receipt proves close eligibility. */
class CashCloseTerminalityPolicy {
    fun assess(sale: SaleSaga, command: OutboxCommand?, receipt: StoreCoreOperationReceipt?, pendingCommands: Int): SaleTerminality {
        require(pendingCommands >= 0)
        if (sale.status == SaleStatus.UNKNOWN || sale.blockSameOperationRepost) return SaleTerminality.Unknown
        if (sale.status !in setOf(SaleStatus.COMMITTED, SaleStatus.RELEASED) || pendingCommands > 0) return SaleTerminality.Pending
        val reservation = sale.evidence ?: return SaleTerminality.Unknown
        if (command == null || receipt == null || sale.recoverWithGet || sale.retired) return SaleTerminality.Unknown
        val kind = if (sale.status == SaleStatus.COMMITTED) StoreCoreOperationKind.COMMIT else StoreCoreOperationKind.RELEASE
        val state = if (sale.status == SaleStatus.COMMITTED) StoreCoreOperationState.COMMITTED else StoreCoreOperationState.RELEASED
        val payload = command.payload as? CanonicalCommandPayload.Terminal ?: return SaleTerminality.Unknown
        return if (command.quadruple == sale.quadruple && receipt.quadruple == sale.quadruple &&
            command.kind == kind && receipt.kind == kind && receipt.state == state &&
            payload.reservationRef == reservation.reservationRef && command.reservationRef == reservation.reservationRef &&
            receipt.reservationRef == reservation.reservationRef && !receipt.receipt.isNullOrBlank() &&
            command.canonicalPath == receipt.contract.canonicalPath && command.contractVersion == reservation.contractVersion &&
            receipt.contract.contractVersion == reservation.contractVersion && command.openapiDigest == reservation.openapiDigest &&
            receipt.contract.openapiDigestSha256 == reservation.openapiDigest && receipt.acceptedPriceVersions == reservation.acceptedPriceVersions
        ) SaleTerminality.Proven else SaleTerminality.Unknown
    }
}
