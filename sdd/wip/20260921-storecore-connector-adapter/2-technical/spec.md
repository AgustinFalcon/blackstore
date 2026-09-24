# Technical Spec — StoreCore connector adapter

**Status:** `ready_for_sol_review` · **No approved**

<!-- extends: sdd/wip/20260921-blackstore-pilot/2-technical/spec.md#isolation-and-external-approval -->
<!-- extends: sdd/wip/20260921-blackstore-pilot/2-technical/spec.md#durable-aggregate-saga -->

## Architecture and composition

```text
BlackStore domain (sale/cash rules; no HTTP/framework)
  -> application port (catalog, reserve, commit, release, GET, reconcile)
  -> infrastructure StoreCore HTTP adapter
     -> TLS + service bearer + canonical four headers
     -> DTO codecs/mappers + BaseResponse validator
  -> composition root selects fixture or HTTP adapter only after gates
```

The domain owns business transitions and receives contract-neutral values/errors. Application owns ports, orchestration, compatibility preconditions, and recovery decisions. Infrastructure owns HTTP, DTO mapping, retry classification, secret-reference integration, and redacted telemetry. Composition selects an explicit adapter; fixtures remain available to tests. No framework/HTTP/DTO type crosses into the domain. Planned work remains in existing BlackStore backend domain/application/infrastructure/configuration boundaries; this WIP invents neither implementation class names nor a second contract file.

## Compatibility lock and request contract

The done compatibility lock (TASK-ADP-002) pins path `/blackstore-integration/v1`, version `1.0.0-draft`, and SHA-256 `7b907a2e11c52a66b7253407fb3f9450cae7b792beccf34c1636be9d3945de30` as constants and fails closed when `blackstore.storecore.contract` disagrees. It does not parse the StoreCore YAML, does not require `info.x-canonical`, and does not persist outbox/inbox evidence. YAML parse, `x-canonical`, headers, scopes, and durable outbox/inbox belong to later tasks. Any new version/digest requires Sol re-review before transport.

Every StoreCore call uses TLS and the canonical base path. It includes Bearer authorization and `X-Client-Instance-Id`, `X-Device-Id`, `X-Sale-Id`, `X-Operation-Id`; the latter four are the canonical quadruple and stay unchanged for reserve/commit/release/GET. Only safe correlation is forwarded when the canonical contract permits it. No payment/fiscal payload or secret value is transmitted.

## Response, state, and recovery mapping

The response mapper validates complete `BaseResponse` before data mapping: HTTP status equals `code`, all six members exist, 200 has data/explicit-null error fields, and errors have null data/non-null `errorCode`, `retryable`, `message`, `traceId`. Messages are redacted diagnostics only, never behavior inputs.

| Remote outcome | Durable BlackStore action | Next action |
|---|---|---|
| timeout/uncertain delivery | retain command/evidence | GET same quadruple before POST |
| 200 PENDING, null receipt | inbox PENDING; no fabricated receipt | same-quadruple recovery only |
| 200 RESERVED/COMMITTED/RELEASED | atomically store receipt, reservation ref, version, digest, accepted price versions | guarded transition; no completed-step POST |
| 200 EXPIRED `OperationReceiptDurable` | atomically persist receipt, reservationRef, contractVersion, digest, and acceptedPriceVersions; preserve evidence and project `RECONCILIATION_REQUIRED` with non-empty `reconciliation_reason` | never re-POST or reuse this operation; a new sale uses a new operation ID |
| 404 after claim-deleting business error | retain error/evidence; same `operation_id` must not POST again | caller mints a new operation ID, same sale ID |
| 409 retryable `CONFLICT` | GET same quadruple and store that receipt before any follow-up POST. `PENDING` stays pending with no invented evidence. `RESERVED`, `COMMITTED`, and `RELEASED` replace the durable tuple (reservation ref, receipt, digest, accepted price versions). Do not invent a new operation ID | Same-body POST once (`sameBodyRetries < 1`) only when the stored step is still incomplete, using the stored ref. Reserve POSTs when the GET is still `PENDING`. Commit does not POST when the GET is already `RELEASED`. Release does not POST when the GET is already `COMMITTED`. The gate does not read envelope `retryable`. A null GET is GET-only and does not POST. `recoverWithGet` is not a GET-only latch after a stored incomplete GET. |
| mismatch | retain original evidence | no mutation; corrected/new sale gets new ID |
| 410 `OPERATION_RETIRED` | append terminal audit/evidence | never re-POST |
| reconcile unknown | record reconciliation-needed evidence | individual GET; unknown alone never re-POSTs |

Outbox precedes HTTP and persists path/version/digest/hash, operation kind, quadruple, attempts, and safe correlation. Inbox deduplicates quadruple + operation kind + response hash and accepts PENDING with null receipt. Reconcile remains StoreCore-read-only; locally it can append audit/reconciliation-needed state but never changes remote inventory or fabricates replacement operations.

## Transport, identity, resilience, and protection

Configuration contains endpoint/TLS trust, opaque StoreCore identity/secret references, expected binding, timeouts, retry bounds, and capability/kill-switch state. The secret integration resolves only at runtime; tokens, headers, DSNs, protected payloads, and secret references are not logged. Certificate and hostname validation are required; invalid TLS/identity/scope/binding/capability fails closed before dispatch.

Retry honors `Retry-After` and a bound. A completed HTTP 429 repeats the same-body POST because the status is 429 (the envelope `retryable` flag is not read on that branch). An uncertain/thrown POST must GET and must not re-POST. It is never a replacement POST with a new operation ID. Contract rate limits remain reserve 30 rps/burst 10, catalog/stock read 60 rps, reconcile 5 rps. Observability records operation kind, version/digest, outcome class, retry count, latency, redacted trace ID, and safe identifiers. Audit records configuration/rotation/kill-switch actor/reason. PAN/CVV/track, fiscal information, secrets, and unnecessary PII are prohibited from DB, inbox/outbox, telemetry, fixtures, and logs.

