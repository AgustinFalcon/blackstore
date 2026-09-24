VERDICT: APPROVED

Independent SDD re-review after the r2 honesty rewrite. Not a GitHub PR review and not merge approval. No fixes applied.

## Why

The single r2 gap is closed. In `2-technical/spec.md`, "Compatibility lock and request contract" now says the done lock (TASK-ADP-002) pins path `/blackstore-integration/v1`, version `1.0.0-draft`, and SHA-256 `7b907a2e11c52a66b7253407fb3f9450cae7b792beccf34c1636be9d3945de30` as constants and fails closed when `blackstore.storecore.contract` disagrees. It says this task does not parse the StoreCore YAML, does not require `info.x-canonical`, and does not persist outbox/inbox evidence. YAML parse, `x-canonical`, headers, scopes, and durable outbox/inbox stay later tasks.

`tasks.json` TASK-ADP-002 GATE is `pin/config assertCompatible + StoreCoreContractCompatibilityGuard tests pass`. Description and AC-1 match that narrower lock. `evidence/TASK-ADP-002-pin.md` matches: the same three constants, `assertCompatible` on configured path/version/sha256, `STORECORE_CONTRACT_INCOMPATIBLE` on drift, no YAML parse, no HTTP client.

Feature-level AC-ADP-2 still names headers, scopes, and BaseResponse for the adapter as a whole. ADP-002 no longer claims those checks are done. Later tasks still own them.

The five r1 fixes are still present.

## Validations run

From `C:\Users\agustin\Desktop\BlackStore\backend`, before this verdict:

```text
.\gradlew.bat test --tests "com.blackstore.domain.StoreCoreCanonicalContractTest" --tests "com.blackstore.connector.StoreCoreRecoveryPolicyTest" --tests "com.blackstore.architecture.ArchitectureBoundaryTest"
```

BUILD SUCCESSFUL. 15 tests, 0 failures, 0 errors:

| Suite | tests | failures |
|---|---|---|
| `StoreCoreCanonicalContractTest` | 2 | 0 |
| `StoreCoreRecoveryPolicyTest` | 6 | 0 |
| `ArchitectureBoundaryTest` | 7 | 0 |

## Fixes still present

1. `actionForError` maps `IDEMPOTENCY_PAYLOAD_MISMATCH` to `RETAIN_DURABLE_EVIDENCE`, `CONFLICT` to `GET_SAME_QUADRUPLE`, and every other unnamed code, including `UNKNOWN_CODE`, to `NEVER_REPOST`. `retiredAndUnknownNeverRepost` asserts mismatch and `UNKNOWN_CODE` are not `GET_SAME_QUADRUPLE`.
2. `StoreCoreOperationReceipt` rejects null or blank `openapiDigestSha256` for RESERVED, COMMITTED, RELEASED, and EXPIRED. `StoreCoreRemoteEvidence` rejects it for EXPIRED. `durableReceiptRejectsMissingDigest` covers EXPIRED null and RESERVED blank.
3. `StoreCoreContractCompatibilityGuard` calls `assertCompatible` on configured path, version, and sha256. Drift throws `STORECORE_CONTRACT_INCOMPATIBLE`. The unit test covers a bad digest, a bad version, and the guard.
4. UX functional spec, `20260923-blackstore-next-gate.md`, connector `meta.md` implementation line (`local_ports_and_pin`), UX plan, and UX `tasks.json` (`gated_visual_dump` / `ux_ang_applied`) still match the five-route dump. ADP-001 names PIC-001..008 only. Status stays `ready_for_sol_review`, not approved. `/sdd.finish` was not run.
5. `storeCoreContractBoundaryHasNoHttpOrFrameworkTypes` includes `application.storecore` and `application.dto.storecore`. The suite passed.

## Required fixes

None for this slice.

## Residual NO-GO

Leave these undone. They are not defects in this slice:

- ADP-005 live transport, TLS, bearer, Retry-After, outbox-before-HTTP, saga wiring, fixtures suite, rollout (ADP-006..010 and L3).
- Fiscal/ARCA, MP-LIVE-05, live companion, secrets, tag, deploy, publish.
- POS-06/07/08 and routes `/sesion`, `/caja/cierre`, `/ticket/:saleId`.
- Archive or `/sdd.finish`. `ready_for_sol_review` is not a Sol GO for live calls.
