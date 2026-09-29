# Technical Spec — BlackStore pilot

**Status:** `ready_for_sol_review`

## Isolation and external approval

One BlackStore deployment maps to exactly one StoreCore installation, has separate process/container/DB/DB role/service identity/secrets/audit, and talks only through a versioned API. Direct cross-DB access is prohibited. The OpenAPI is proposed only: TASK-005/006 implement port/DTO/fixture simulator; the real connector is blocked by `DEFERRED-STORECORE-CONNECTOR-001` pending StoreCore Sol GO and a separate formal adapter implementation WIP/task. This deferred item authorizes no HTTP connector code.

## Durable aggregate saga

A whole sale/reservation persists `client_instance_id`, `device_id`, `sale_id`, `operation_id` (UNIQUE like StoreCore), plus `canonical_path=/blackstore-integration/v1`, `contract_version` and `openapi_digest`. `aggregate_operation_key` is generated/enforced equal to `operation_id`. Outbox commands persist `canonical_path`, `contract_version`, `openapi_digest` and `request_hash` at insert time; retries never re-infer them. Local `operation_kind` is RESERVE|COMMIT|RELEASE only. Before RESERVE, transaction inserts immutable sale intent, PENDING_RESERVATION projection, snapshots/audit and outbox. Timeout: GET same quadruple.

StoreCore JSON is the complete AssistTime `BaseResponse`: HTTP status equals `code` because each status schema fixes `code` with `const`; every non-304 JSON requires `code,data,errorCode,retryable,message,traceId`. Success is `code=200` with payload `data` and explicit null `errorCode`/`retryable`/`message`. Errors have `data=null` and non-null `errorCode`, `retryable`, `message`, and `traceId`; `errorCode` is the 409 matrix discriminator. Do not parse `message`.

409 matrix: `INSUFFICIENT_STOCK` / `CATALOG_VERSION_STALE` / `VALIDATION` → new `operation_id`, same `sale_id`. `CONFLICT` → retry same quadruple; preserves prior durable state (PENDING on reserve, RESERVED/COMMITTED on commit/release). `IDEMPOTENCY_PAYLOAD_MISMATCH` / `EXPIRED` → keep original evidence; new sale uses a new id. GET 410 `OPERATION_RETIRED` (`retryable=false`) → never re-POST (tombstone); a tombstone or unknown result from read-only reconcile never authorizes re-POST. Inbox stores PENDING with null receipt. Reconcile is read-only comparison of caller-supplied receipts (zero writes).

## Mutability and guards

`blackstore_app` has narrow INSERT/SELECT grants for initial sale intent, PENDING_RESERVATION projection, snapshots/lines/payments, immutable events, outbox command and audit; all occur in one transaction. Initial-projection triggers reject advanced state/reservation data. It has no UPDATE/DELETE on historical tables or projections. `blackstore_projection_worker` applies guarded UPDATE transitions and may INSERT **only** a missing initial PENDING_RESERVATION row during event replay; it never inserts recovered advanced states. `blackstore_outbox_worker` alone updates `outbox_delivery_attempts`. `blackstore_migration_owner` is never a runtime role. SQL guards reject mutation/deletion, invalid sale/cash transitions and invalid cash zero/event semantics. V4 records a sale in production without a fiscal authorization. Migration/runtime-role smoke test must prove each permitted INSERT succeeds and historical UPDATE/DELETE fails under runtime roles.

## Data protection, fiscal and reports

No PAN/CVV/track/acquirer secret may enter DB, inbox/outbox or logs. Payments use provider token/reference and optional brand/last4. PII has purpose/minimization/retention/audit. A counter sale is recorded in test and in production. Fiscal status does not block it. This pilot does not emit an invoice. Sales are never hidden, deleted, or double-booked. Online sales stay in StoreCore.

`gross_sales` precedes discounts; `net_sales=gross_sales-discounts`; refunds/collected/fees/expenses are distinct. Report projections require versioned configuration, period, inputs and completeness. They are not tax result/free cash.
