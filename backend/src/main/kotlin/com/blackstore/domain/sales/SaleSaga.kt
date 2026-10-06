package com.blackstore.domain.sales

import com.blackstore.domain.exception.ForbiddenOperationException
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.model.StoreCoreOperationKind
import com.blackstore.domain.model.StoreCoreOperationState
import java.math.BigDecimal
import java.time.Instant

enum class SaleStatus(val label: String) {
    PENDING_RESERVATION("Reserva pendiente"),
    RESERVED("Reservado"),
    PAYMENT_CAPTURED("Pago capturado"),
    COMMIT_PENDING("Confirmación pendiente"),
    COMMITTED("Confirmado"),
    RELEASE_PENDING("Liberación pendiente"),
    RELEASED("Liberado"),
    RECONCILIATION_REQUIRED("Requiere conciliación"),
    UNKNOWN("Estado no disponible");
    companion object { fun fromWire(value: String?): SaleStatus = entries.firstOrNull { it.name == value && it != UNKNOWN } ?: UNKNOWN }
}

enum class FiscalStatus {
    NOT_CONFIGURED,
    PENDING,
    ISSUED,
    FAILED_RECONCILIATION,
}

data class OutboxCommand(
    val quadruple: OperationQuadruple,
    val kind: StoreCoreOperationKind,
    val canonicalPath: String,
    val contractVersion: String,
    val openapiDigest: String,
    val requestHash: String,
    val reservationRef: String? = null,
)

data class RemoteEvidence(
    val reservationRef: String,
    val receipt: String,
    val contractVersion: String,
    val openapiDigest: String,
    val acceptedPriceVersions: List<String>,
    val expiresAt: Instant?,
) {
    init {
        require(reservationRef.isNotBlank() && receipt.isNotBlank())
        require(contractVersion.isNotBlank() && openapiDigest.isNotBlank())
        require(acceptedPriceVersions.isNotEmpty())
    }
}

data class SaleSaga(
    val quadruple: OperationQuadruple,
    val cashSessionId: Long,
    val status: SaleStatus = SaleStatus.PENDING_RESERVATION,
    val evidence: RemoteEvidence? = null,
    val reconciliationReason: String? = null,
    val outbox: List<OutboxCommand> = emptyList(),
    val fiscalStatus: FiscalStatus = FiscalStatus.NOT_CONFIGURED,
    val grossSales: BigDecimal = BigDecimal.ZERO,
    val discounts: BigDecimal = BigDecimal.ZERO,
    val refunds: BigDecimal = BigDecimal.ZERO,
    val retired: Boolean = false,
    val recoverWithGet: Boolean = false,
    val blockSameOperationRepost: Boolean = false,
    val sameBodyRetries: Int = 0,
    val lines: List<TicketLine> = emptyList(),
    val createdBy: Long? = null,
    val staffCommandAudit: SaleStaffCommandAudit? = null,
) {
    val netSales: BigDecimal get() = grossSales.subtract(discounts)

    init {
        require(discounts.signum() >= 0 && discounts <= grossSales)
        if (status == SaleStatus.RECONCILIATION_REQUIRED) {
            require(!reconciliationReason.isNullOrBlank() && reconciliationReason.trim().length >= 3) {
                "reconciliation reason is required"
            }
        }
        if (status in SUCCESS_STATES) {
            require(evidence != null) { "successful sale state requires the full remote evidence tuple" }
        }
        if (status == SaleStatus.PENDING_RESERVATION) {
            require(evidence == null && reconciliationReason == null)
        }
    }

    fun withReserveCommand(command: OutboxCommand): SaleSaga {
        require(status == SaleStatus.PENDING_RESERVATION)
        require(command.kind == StoreCoreOperationKind.RESERVE)
        return copy(outbox = outbox + command)
    }

    fun markReserved(evidence: RemoteEvidence): SaleSaga {
        require(status == SaleStatus.PENDING_RESERVATION)
        require(outbox.any { it.kind == StoreCoreOperationKind.RESERVE }) {
            "reserve outbox command must exist before the reservation result"
        }
        return copy(status = SaleStatus.RESERVED, evidence = evidence, recoverWithGet = false)
    }

    fun applyRemoteDurable(state: StoreCoreOperationState, evidence: RemoteEvidence): SaleSaga =
        when (state) {
            StoreCoreOperationState.RESERVED ->
                if (status == SaleStatus.PENDING_RESERVATION) {
                    markReserved(evidence)
                } else {
                    copy(evidence = evidence, recoverWithGet = false)
                }
            StoreCoreOperationState.COMMITTED ->
                copy(status = SaleStatus.COMMITTED, evidence = evidence, recoverWithGet = false)
            StoreCoreOperationState.RELEASED ->
                copy(status = SaleStatus.RELEASED, evidence = evidence, recoverWithGet = false)
            else -> this
        }

    fun withCommand(command: OutboxCommand): SaleSaga {
        require(command.kind == StoreCoreOperationKind.COMMIT || command.kind == StoreCoreOperationKind.RELEASE)
        if (outbox.any { it.kind == command.kind }) return this
        return copy(outbox = outbox + command)
    }

    fun markPaymentCaptured(): SaleSaga {
        require(status == SaleStatus.RESERVED && evidence != null)
        return copy(status = SaleStatus.PAYMENT_CAPTURED)
    }

    fun markCommitPending(): SaleSaga {
        require(status == SaleStatus.PAYMENT_CAPTURED)
        require(outbox.any { it.kind == StoreCoreOperationKind.COMMIT }) {
            "commit outbox command must exist before commit pending"
        }
        return copy(status = SaleStatus.COMMIT_PENDING)
    }

    fun markCommitted(): SaleSaga {
        require(status == SaleStatus.COMMIT_PENDING && evidence != null)
        return copy(status = SaleStatus.COMMITTED)
    }

    fun markReleasePending(): SaleSaga {
        require(status == SaleStatus.RESERVED || status == SaleStatus.PAYMENT_CAPTURED)
        require(outbox.any { it.kind == StoreCoreOperationKind.RELEASE }) {
            "release outbox command must exist before release pending"
        }
        return copy(status = SaleStatus.RELEASE_PENDING)
    }

    fun markReleased(): SaleSaga {
        require(status == SaleStatus.RELEASE_PENDING && evidence != null)
        return copy(status = SaleStatus.RELEASED)
    }

    fun markReconciliationRequired(reason: String, evidence: RemoteEvidence): SaleSaga {
        require(
            status in
                setOf(
                    SaleStatus.PENDING_RESERVATION,
                    SaleStatus.RESERVED,
                    SaleStatus.PAYMENT_CAPTURED,
                    SaleStatus.COMMIT_PENDING,
                    SaleStatus.RELEASE_PENDING,
                ),
        )
        require(outbox.any { it.kind == StoreCoreOperationKind.RESERVE }) {
            "reserve outbox command must exist before reconciliation"
        }
        require(reason.trim().length >= 3)
        return copy(
            status = SaleStatus.RECONCILIATION_REQUIRED,
            evidence = evidence,
            reconciliationReason = reason.trim(),
            recoverWithGet = false,
            blockSameOperationRepost = true,
        )
    }

    fun markGetOnly(): SaleSaga = copy(recoverWithGet = true)

    fun markSameOperationBlocked(): SaleSaga = copy(blockSameOperationRepost = true)

    fun markRetired(): SaleSaga {
        if (retired) return this
        return copy(retired = true)
    }

    companion object {
        val SUCCESS_STATES =
            setOf(
                SaleStatus.RESERVED,
                SaleStatus.PAYMENT_CAPTURED,
                SaleStatus.COMMIT_PENDING,
                SaleStatus.COMMITTED,
                SaleStatus.RELEASE_PENDING,
                SaleStatus.RELEASED,
            )
    }
}

class RetiredOperationPolicy {
    fun assertCanPost(retired: Boolean) {
        if (retired) {
            throw ForbiddenOperationException("OPERATION_RETIRED is not retryable and must not be re-posted")
        }
    }
}
