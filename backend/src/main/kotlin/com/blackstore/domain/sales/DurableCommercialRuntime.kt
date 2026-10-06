package com.blackstore.domain.sales

import com.blackstore.domain.model.OperationQuadruple
import java.time.Instant
import java.util.UUID

enum class CommandKind { RESERVE, COMMIT, RELEASE, UNKNOWN }
enum class DeliveryState { PENDING, IN_FLIGHT, UNCERTAIN, APPLIED, RECONCILIATION_REQUIRED, LEGACY_INCOMPLETE, UNKNOWN }
enum class RecoveryReason { LEASE_EXPIRED, REMOTE_PENDING, REMOTE_UNAVAILABLE, EXPIRED, PAYLOAD_MISMATCH, CONTRACT_INCOMPATIBLE, TERMINAL_CONTRADICTION, NOT_FOUND_TERMINAL, ATTEMPTS_EXHAUSTED, INCOMPLETE_EVIDENCE, UNKNOWN }
enum class AttemptOutcome { APPLIED, LATE_IGNORED, WAIT_AND_GET, RETRY, RECONCILIATION_REQUIRED, UNKNOWN }
sealed interface RecoveryDisposition {
 data object ApplyEvidence : RecoveryDisposition
 data object RetrySameCommand : RecoveryDisposition
 data object WaitAndGet : RecoveryDisposition
 data class ReconciliationRequired(val reason: RecoveryReason) : RecoveryDisposition
 data object Unknown : RecoveryDisposition
}
enum class DurableSaleState { PENDING_RESERVATION, RESERVED, PAYMENT_CAPTURED, COMMIT_PENDING, COMMITTED, RELEASE_PENDING, RELEASED, RECONCILIATION_REQUIRED, LEGACY_INCOMPLETE, UNKNOWN }
data class CanonicalReserveLine(val variantId: String, val quantity: Int, val expectedPriceVersion: String) {
 init { require(variantId.isNotBlank() && quantity > 0 && expectedPriceVersion.isNotBlank()) }
}
sealed interface CanonicalCommandPayload {
 data class Reserve(val catalogVersion: String, val lines: List<CanonicalReserveLine>) : CanonicalCommandPayload {
  init { require(catalogVersion.isNotBlank() && lines.isNotEmpty()) }
 }
 data class Terminal(val reservationRef: String) : CanonicalCommandPayload {
  init { require(reservationRef.isNotBlank()) }
 }
}
data class StoredSale(val saga: SaleSaga, val version: Long, val state: DurableSaleState, val projectionId: Long)
data class ClaimedSaleCommand(val id: Long, val command: OutboxCommand, val claimToken: UUID, val claimEpoch: Long,
 val uncertain: Boolean, val attempts: Int, val actorId: Long, val cashSessionId: Long)
