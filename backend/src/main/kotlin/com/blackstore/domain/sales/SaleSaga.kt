package com.blackstore.domain.sales

import com.blackstore.domain.exception.ForbiddenOperationException
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.model.StoreCoreOperationKind
import java.math.BigDecimal
import java.time.Instant

enum class SaleStatus {
    PENDING_RESERVATION,
    RESERVED,
    PAYMENT_CAPTURED,
    COMMIT_PENDING,
    COMMITTED,
    RELEASE_PENDING,
    RELEASED,
    RECONCILIATION_REQUIRED,
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
    val lines: List<TicketLine> = emptyList(),
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
        return copy(status = SaleStatus.RESERVED, evidence = evidence)
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
