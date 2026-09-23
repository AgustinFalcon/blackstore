# Functional Spec — StoreCore connector adapter

**Status:** `ready_for_sol_review` · **No approved**

<!-- extends: sdd/wip/20260921-blackstore-pilot/1-functional/spec.md#acceptance-criteria -->

## Purpose and boundary

This feature turns BlackStore's application port into a StoreCore HTTP adapter only after the two gates below. StoreCore remains authority for catalog, authorized prices/costs, and inventory; BlackStore remains authority for ticket, cash, expenses, audits, and reports. The adapter never reaches a StoreCore database, copies a ticket, or sends/stores PAN, payment PII, fiscal data, or fiscal documents.

The sole wire contract is StoreCore's `blackstore-integration.openapi.yaml`, `1.0.0-draft`, prefix `/blackstore-integration/v1`; its SHA-256 digest is pinned before calls. Changed/removed paths, operations, required fields/headers, enums/states/errorCodes, nullability, status/code rules, scopes, idempotency, rate rules, or response meaning are incompatible until a new compatibility decision and Sol review. A new major version is incompatible. Optional additions require schema/fixture verification. BlackStore creates no OpenAPI copy.

## Start gate and producer/consumer handoff

No implementation starts until StoreCore's readiness handoff contains passing evidence and this WIP has explicit Sol GO. StoreCore GO alone does not authorize BlackStore; BlackStore GO alone does not authorize it either. Before both gates, only documentation and planning are allowed; existing pilot fixtures may be read as evidence only, not created or modified. No ports, DTOs, fixtures, HTTP, secret use, Flyway, deployment, or production integration code.

| StoreCore producer task | Required accepted evidence | BlackStore task unlocked |
|---|---|---|
| `TASK-PIC-001` | DISABLED capability, one companion binding, identity, scopes, TLS, active-secret rotation | `TASK-ADP-001`, `TASK-ADP-008` |
| `TASK-PIC-002` | durable quadruple uniqueness and `EXTERNAL_BLACKSTORE` ledger | `TASK-ADP-006`, `TASK-ADP-010` |
| `TASK-PIC-003` | catalog/stock reads, validUntil, price versions, sellable stock | `TASK-ADP-003`, `TASK-ADP-009` |
| `TASK-PIC-004` | reserve Tx-A/Tx-B, PENDING GET, idempotency/error matrix | `TASK-ADP-004` through `TASK-ADP-010` |
| `TASK-PIC-005` | commit/release/expiry state machine | `TASK-ADP-006`, `TASK-ADP-010` |
| `TASK-PIC-006` | GET recovery, read-only reconcile, tombstone/410 | `TASK-ADP-005`, `TASK-ADP-006`, `TASK-ADP-010` |
| `TASK-PIC-007` | BaseResponse/error/rate-limit/redaction behavior | `TASK-ADP-004`, `TASK-ADP-005`, `TASK-ADP-009` |
| `TASK-PIC-008` | canonical YAML/version header/additive-only diff | `TASK-ADP-002` through `TASK-ADP-L3-003` |

The handoff names accepted task IDs, YAML path/version/SHA-256, capability state, installation/service identity references without secrets, test/contract evidence, and Sol's explicit GO.

## Required behavior

1. Before Reserve, BlackStore writes intent, canonical path, version/digest, request hash, and quadruple `(client_instance_id, device_id, sale_id, operation_id)` to its own outbox. The domain has no HTTP/framework dependency.
2. Every call uses TLS, service bearer identity, `X-Client-Instance-Id`, `X-Device-Id`, `X-Sale-Id`, and `X-Operation-Id`. Authorization is scopes-only; actor data is audit-only. Secrets are opaque references, never values in logs, fixtures, events, or evidence.
3. The adapter completely unwraps non-304 `BaseResponse`: required `code`, `data`, `errorCode`, `retryable`, `message`, `traceId`; HTTP status equals `code`. Success has data plus explicit null error fields. Error has null data plus non-null error fields. Behavior branches on `errorCode`, never a message.
4. Timeout/uncertain delivery first GETs the same quadruple. PENDING persists with null receipt and may retry the same quadruple. RESERVED/COMMITTED/RELEASED require receipt, reservation ref, version, digest, and accepted price versions. 410 `OPERATION_RETIRED` is terminal: preserve evidence and never re-POST. Reconcile is caller-supplied, bounded, and read-only; unknown alone never permits re-POST.
5. `INSUFFICIENT_STOCK`, `CATALOG_VERSION_STALE`, `VALIDATION` retain evidence and need a new operation ID with the same sale ID. `CONFLICT` retries the same quadruple. `IDEMPOTENCY_PAYLOAD_MISMATCH` retains original evidence. `EXPIRED` is an `OperationReceiptDurable`: it requires receipt, reservationRef, contractVersion, digest, and acceptedPriceVersions; BlackStore projects it to `RECONCILIATION_REQUIRED` with a non-empty `reconciliation_reason` and the complete remote tuple. A new sale has a new ID. Retry only transport faults and retryable envelopes, honor `Retry-After` and bounded backoff, and never retry non-retryable/tombstone results.
6. Disabled capability, binding mismatch, absent identity/scope/TLS, failed compatibility lock, or active kill switch fails closed before a network call. New integrated sales block safely; ticket/audit/outbox evidence remains intact.

## Credential, configuration, rollout, and migration lifecycle

1. Sol approves the StoreCore readiness handoff and this WIP; Terra records contract lock and environment references.
2. An authorized operator configures StoreCore identity and opaque secret reference according to `TASK-PIC-001`; BlackStore validates reference/binding and never logs a secret.
3. Rotation follows StoreCore's atomic revoke-then-insert transaction and grace period. BlackStore updates only an audited opaque reference, verifies replacement before retirement, and restores the last valid reference on rollback; no duplicate active credential is created.
4. Rollout begins capability-disabled with kill switch active, validates read-only catalog, then canaries one instance. Expansion requires own-DB migration/rollback evidence, no cross-DB credentials, no unresolved outbox/inbox, contract/metric/redaction evidence, and Sol authorization.
5. Incompatible digest/version, contract failure, error-matrix deviation, credential mismatch, or security signal disables capability/kill switch, stops calls, preserves evidence, and invokes safe GET/reconciliation. It never deletes, rewrites, or silently re-posts evidence.

## Acceptance criteria

- AC-ADP-1: adapter work starts only after both GO/evidence gates; direct StoreCore DB access does not exist.
- AC-ADP-2: `1.0.0-draft`, SHA-256, path, headers, scopes, and BaseResponse semantics are locked and verified.
- AC-ADP-3: port/domain isolation, durable outbox/inbox, response mapping, PENDING/GET recovery, tombstones, and read-only reconcile conform to the canonical contract.
- AC-ADP-4: opaque credentials, TLS, audited rotation, bounded retry, redaction, disabled behavior, and kill switch are enforced.
- AC-ADP-5: rollout/rollback preserve records, prevent duplicate/hidden sales, and retain no PAN, fiscal information, or unnecessary PII.

## Non-goals

No StoreCore server work, StoreCore Flyway/schema work, direct database integration, fiscal adapter, ticket copy, shared tenancy, `store_id`, offline integrated sale, hidden sale, or contract ownership transfer is in scope.
