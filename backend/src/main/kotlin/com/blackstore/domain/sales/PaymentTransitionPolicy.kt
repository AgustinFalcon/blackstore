package com.blackstore.domain.sales

import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.model.StoreCoreOperationKind
import java.math.BigDecimal

object MoneyPolicy {
    private val maximum = BigDecimal("999999999999.99")
    fun valid(value: BigDecimal): Boolean = value.abs() <= maximum && value.stripTrailingZeros().scale() <= 2
    fun normalize(value: BigDecimal): BigDecimal {
        require(valid(value)) { "money must fit NUMERIC(14,2) exactly" }
        return value.setScale(2)
    }
}

enum class PaymentCoverage(val label: String) { Unpaid("Sin pagos"), Partial("Pago parcial"), Paid("Pagado"), InvalidUnknown("Cobertura no disponible") }
enum class TransitionReason(val label: String) {
    SaleMissingOrAmbiguous("Venta ausente o ambigua"), StateIneligible("Estado no elegible"), EvidenceInvalid("Evidencia inválida"),
    LedgerUnknown("Historial no disponible"), MoneyInvalid("Importe inválido"), Overcapture("Importe superior al saldo"),
    CoverageIncomplete("Cobertura incompleta"), PaymentHistory("La venta tiene historial de pagos"),
    OriginalPaymentMismatch("El pago original no pertenece a la venta"), CommandInvalid("Comando original incompatible"),
}
sealed class TransitionDecision {
    data object NewCommand : TransitionDecision()
    data object RecoverExistingCommand : TransitionDecision()
    data object TerminalReplay : TransitionDecision()
    data class Denied(val reason: TransitionReason) : TransitionDecision()
    fun assertAllowed() { require(this !is Denied) { "transition denied: ${(this as Denied).reason.label}" } }
}

data class PaymentLedgerEntry(
    val identity: OperationQuadruple,
    val paymentId: Long,
    val method: PaymentMethod,
    val status: PaymentStatus,
    val amount: BigDecimal?,
    val feeAmount: BigDecimal?,
    val originalPaymentId: Long? = null,
)
sealed class OperationLedger {
    data class Known(val entries: List<PaymentLedgerEntry>) : OperationLedger()
    data object Unknown : OperationLedger()
}
data class PaymentSnapshot(
    val totalAmount: BigDecimal?, val pendingAmount: BigDecimal?,
    val paymentCoverage: PaymentCoverage, val hasPaymentHistory: Boolean, val evidenceValid: Boolean,
)

class PaymentTransitionPolicy {
    fun snapshot(sale: SaleSaga, ledger: OperationLedger): PaymentSnapshot {
        val evidence = sale.evidence
        val validEvidence = evidence != null && evidence.reservationRef.isNotBlank() && evidence.receipt.isNotBlank() &&
            evidence.contractVersion.isNotBlank() && evidence.openapiDigest.isNotBlank() && evidence.acceptedPriceVersions.isNotEmpty()
        val total = sale.lines.takeIf { it.isNotEmpty() }?.fold(BigDecimal.ZERO) { acc, line ->
            acc + line.effectiveUnitPrice.multiply(BigDecimal(line.quantity))
        }?.takeIf { MoneyPolicy.valid(it) && it.signum() > 0 }
        val entries = (ledger as? OperationLedger.Known)?.entries
        val history = entries == null || entries.isNotEmpty()
        if (total == null || entries == null || entries.map { it.paymentId }.distinct().size != entries.size || entries.any {
                it.identity != sale.quadruple || it.method == PaymentMethod.UNKNOWN || it.status != PaymentStatus.CAPTURED ||
                    it.paymentId <= 0 ||
                    it.amount == null || it.feeAmount == null || !MoneyPolicy.valid(it.amount) || !MoneyPolicy.valid(it.feeAmount) ||
                    it.amount.signum() <= 0 || it.feeAmount.signum() < 0 || it.originalPaymentId != null
            }) return PaymentSnapshot(total, null, PaymentCoverage.InvalidUnknown, history, validEvidence)
        val sum = entries.fold(BigDecimal.ZERO) { acc, entry -> acc + entry.amount!! }
        val fees = entries.fold(BigDecimal.ZERO) { acc, entry -> acc + entry.feeAmount!! }
        if (!MoneyPolicy.valid(sum) || !MoneyPolicy.valid(fees) || sum > total) return PaymentSnapshot(total, null, PaymentCoverage.InvalidUnknown, history, validEvidence)
        val coverage = when { entries.isEmpty() -> PaymentCoverage.Unpaid; sum.compareTo(total) == 0 -> PaymentCoverage.Paid; else -> PaymentCoverage.Partial }
        return PaymentSnapshot(MoneyPolicy.normalize(total), MoneyPolicy.normalize(total - sum), coverage, history, validEvidence)
    }

    fun capture(sale: SaleSaga, ledger: OperationLedger, method: PaymentMethod, amount: BigDecimal, fee: BigDecimal): TransitionDecision {
        val eligibility = eligible(sale)
        if (eligibility != null) return eligibility
        if (method == PaymentMethod.UNKNOWN || !MoneyPolicy.valid(amount) || !MoneyPolicy.valid(fee) || amount.signum() <= 0 || fee.signum() < 0)
            return TransitionDecision.Denied(TransitionReason.MoneyInvalid)
        val snapshot = snapshot(sale, ledger)
        if (snapshot.pendingAmount == null) return TransitionDecision.Denied(TransitionReason.LedgerUnknown)
        val fees = (ledger as OperationLedger.Known).entries.fold(fee) { sum, entry -> sum + entry.feeAmount!! }
        if (!MoneyPolicy.valid(fees)) return TransitionDecision.Denied(TransitionReason.MoneyInvalid)
        if (amount > snapshot.pendingAmount) return TransitionDecision.Denied(TransitionReason.Overcapture)
        return TransitionDecision.NewCommand
    }

    fun terminal(sale: SaleSaga, ledger: OperationLedger, kind: StoreCoreOperationKind): TransitionDecision {
        if (sale.retired || sale.blockSameOperationRepost || sale.status == SaleStatus.UNKNOWN || sale.status == SaleStatus.RECONCILIATION_REQUIRED)
            return TransitionDecision.Denied(TransitionReason.StateIneligible)
        val terminal = if (kind == StoreCoreOperationKind.COMMIT) SaleStatus.COMMITTED else SaleStatus.RELEASED
        val pending = if (kind == StoreCoreOperationKind.COMMIT) SaleStatus.COMMIT_PENDING else SaleStatus.RELEASE_PENDING
        if (sale.status == terminal || sale.status == pending) {
            val evidence = sale.evidence ?: return TransitionDecision.Denied(TransitionReason.EvidenceInvalid)
            val commands = sale.outbox.filter { it.kind == kind }
            if (commands.size != 1 || commands.single().let {
                    it.quadruple != sale.quadruple || it.canonicalPath.isBlank() || it.contractVersion != evidence.contractVersion ||
                        it.openapiDigest != evidence.openapiDigest || it.requestHash.isBlank() || it.reservationRef.isNullOrBlank()
                }) return TransitionDecision.Denied(TransitionReason.CommandInvalid)
            return if (sale.status == terminal) TransitionDecision.TerminalReplay else TransitionDecision.RecoverExistingCommand
        }
        eligible(sale)?.let { return it }
        val snapshot = snapshot(sale, ledger)
        return if (kind == StoreCoreOperationKind.COMMIT) {
            if (snapshot.paymentCoverage == PaymentCoverage.Paid) TransitionDecision.NewCommand
            else TransitionDecision.Denied(TransitionReason.CoverageIncomplete)
        } else {
            if (snapshot.paymentCoverage == PaymentCoverage.Unpaid && !snapshot.hasPaymentHistory) TransitionDecision.NewCommand
            else TransitionDecision.Denied(TransitionReason.PaymentHistory)
        }
    }

    fun reverse(sale: SaleSaga, ledger: OperationLedger, paymentId: Long): TransitionDecision {
        eligible(sale)?.let { return it }
        val entries = (ledger as? OperationLedger.Known)?.entries ?: return TransitionDecision.Denied(TransitionReason.LedgerUnknown)
        if (snapshot(sale, ledger).paymentCoverage == PaymentCoverage.InvalidUnknown) return TransitionDecision.Denied(TransitionReason.LedgerUnknown)
        if (entries.count { it.paymentId == paymentId && it.identity == sale.quadruple && it.status == PaymentStatus.CAPTURED } != 1 ||
            entries.any { it.originalPaymentId == paymentId }) return TransitionDecision.Denied(TransitionReason.OriginalPaymentMismatch)
        return TransitionDecision.NewCommand
    }

    private fun eligible(sale: SaleSaga): TransitionDecision.Denied? = when {
        sale.retired || sale.blockSameOperationRepost || sale.status !in setOf(SaleStatus.RESERVED, SaleStatus.PAYMENT_CAPTURED) ->
            TransitionDecision.Denied(TransitionReason.StateIneligible)
        sale.evidence == null -> TransitionDecision.Denied(TransitionReason.EvidenceInvalid)
        else -> null
    }
}
