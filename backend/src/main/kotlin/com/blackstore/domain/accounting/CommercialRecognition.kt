package com.blackstore.domain.accounting

import com.blackstore.domain.model.StoreCoreOperationKind
import com.blackstore.domain.model.StoreCoreOperationReceipt
import com.blackstore.domain.model.StoreCoreOperationState
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.sales.MoneyPolicy
import com.blackstore.domain.sales.SaleSaga
import com.blackstore.domain.sales.SaleStatus
import java.math.BigDecimal
import java.time.Instant

enum class SaleTerminality { Proven, Pending, Unknown }

class AccountingSaleTerminalityPolicy {
    fun assess(sale: SaleSaga, receipt: StoreCoreOperationReceipt?, pendingCommands: Int): SaleTerminality {
        require(pendingCommands >= 0)
        if (sale.status == SaleStatus.UNKNOWN || receipt == null) return SaleTerminality.Unknown
        if (sale.status !in setOf(SaleStatus.COMMITTED, SaleStatus.RELEASED) || pendingCommands > 0) return SaleTerminality.Pending
        val evidence = sale.evidence ?: return SaleTerminality.Unknown
        val expectedKind = if (sale.status == SaleStatus.COMMITTED) StoreCoreOperationKind.COMMIT else StoreCoreOperationKind.RELEASE
        val expectedState = if (sale.status == SaleStatus.COMMITTED) StoreCoreOperationState.COMMITTED else StoreCoreOperationState.RELEASED
        return if (receipt.quadruple == sale.quadruple && receipt.kind == expectedKind && receipt.state == expectedState &&
            receipt.reservationRef == evidence.reservationRef && receipt.receipt == evidence.receipt &&
            receipt.contract.contractVersion == evidence.contractVersion && receipt.contract.openapiDigestSha256 == evidence.openapiDigest &&
            receipt.acceptedPriceVersions == evidence.acceptedPriceVersions && !sale.recoverWithGet && !sale.blockSameOperationRepost
        ) SaleTerminality.Proven else SaleTerminality.Unknown
    }
}

data class CommercialRecognition(val identity: OperationQuadruple, val grossSales: BigDecimal, val discounts: BigDecimal, val recognizedAt: Instant) {
    init {
        require(grossSales.signum() >= 0 && discounts.signum() >= 0 && discounts <= grossSales)
        MoneyPolicy.normalize(grossSales)
        MoneyPolicy.normalize(discounts)
    }
    val netSales: BigDecimal get() = MoneyPolicy.normalize(grossSales - discounts)
}

class CommercialRecognitionPolicy {
    fun recognize(sale: SaleSaga, receipt: StoreCoreOperationReceipt, pendingCommands: Int, at: Instant): CommercialRecognition {
        require(sale.status == SaleStatus.COMMITTED && AccountingSaleTerminalityPolicy().assess(sale, receipt, pendingCommands) == SaleTerminality.Proven)
        require(sale.lines.isNotEmpty())
        val gross = sale.lines.fold(BigDecimal.ZERO) { sum, line -> sum + line.originalUnitPrice.multiply(BigDecimal(line.quantity)) }
        val discounts = sale.lines.fold(BigDecimal.ZERO) { sum, line -> sum + line.discountAmount.multiply(BigDecimal(line.quantity)) }
        return CommercialRecognition(sale.quadruple, MoneyPolicy.normalize(gross), MoneyPolicy.normalize(discounts), at)
    }
}
