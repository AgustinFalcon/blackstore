VERDICT: CHANGES_REQUIRED

Independent SDD re-review of the BlackStore uncommitted tranche after the five r1 fixes. Not a GitHub PR review and not merge approval. No fixes applied.

## Why

The five code fixes are present and the focused tests pass. Recovery no longer sends mismatch or unnamed codes to GET. Durable receipts and EXPIRED evidence reject a blank digest. Configured path, version, and sha256 fail closed against the pin. ArchUnit covers the new application packages. The named SDD files no longer claim an empty Angular dump or a local PIC-009/L3 close.

The connector technical spec was only re-pinned. It still says the first executable task reads the canonical YAML, requires `info.x-canonical=true`, and persists outbox/inbox evidence. `TASK-ADP-002` is `done` with the opposite record: constants plus config fail-close; the YAML is not parsed; headers, scopes, and `x-canonical` stay unpinned. That is the same class of SDD contradiction as r1.

## Validations run

From `C:\Users\agustin\Desktop\BlackStore\backend`, before this verdict:

```text
.\gradlew.bat test --tests "com.blackstore.connector.StoreCoreRecoveryPolicyTest" --tests "com.blackstore.connector.StoreCoreEnvelopeValidatorTest" --tests "com.blackstore.architecture.ArchitectureBoundaryTest" --tests "com.blackstore.domain.StoreCoreCanonicalContractTest"
```

BUILD SUCCESSFUL. 20 tests, 0 failures, 0 errors:

| Suite | tests | failures |
|---|---|---|
| `StoreCoreCanonicalContractTest` | 2 | 0 |
| `StoreCoreEnvelopeValidatorTest` | 5 | 0 |
| `StoreCoreRecoveryPolicyTest` | 6 | 0 |
| `ArchitectureBoundaryTest` | 7 | 0 |

`Get-FileHash -Algorithm SHA256` on both canonical copies returned `7B907A2E11C52A66B7253407FB3F9450CAE7B792BECCF34C1636BE9D3945DE30`, matching `StoreCoreCanonicalContract.SHA256` and `application.yml`. BlackStore still does not parse that file.

`frontend/src/app/app.routes.ts` is ``, `caja`, `catalogo`, `ticket`, `reportes`, plus wildcard redirect. Grep of `frontend/src` for `blackstore-integration`, `/sesion`, `/caja/cierre`, and `ticket/:`: no matches. Grep of `backend/src/main` for `RestTemplate`, `WebClient`, `java.net.http`, `OkHttpClient`, and `HttpClient(`: no matches.

## Fixes checked

1. `actionForError` maps `IDEMPOTENCY_PAYLOAD_MISMATCH` to `RETAIN_DURABLE_EVIDENCE`, `CONFLICT` to `GET_SAME_QUADRUPLE`, and every other unnamed code, including `UNKNOWN_CODE`, to `NEVER_REPOST`. `retiredAndUnknownNeverRepost` asserts mismatch and `UNKNOWN_CODE` are not `GET_SAME_QUADRUPLE`.
2. `StoreCoreOperationReceipt` rejects null or blank `openapiDigestSha256` for RESERVED, COMMITTED, RELEASED, and EXPIRED. `StoreCoreRemoteEvidence` rejects it for EXPIRED. `durableReceiptRejectsMissingDigest` covers EXPIRED null and RESERVED blank.
3. `StoreCoreContractCompatibilityGuard` calls `assertCompatible` on configured path, version, and sha256. Drift throws `STORECORE_CONTRACT_INCOMPATIBLE`. The unit test covers a bad digest, a bad version, and the guard. A drifted path is the same predicate and is not separately asserted. `TASK-ADP-002` description, AC-1, and `evidence/TASK-ADP-002-pin.md` say the YAML is not parsed and that headers, scopes, and `x-canonical` stay unpinned.
4. UX functional spec, `20260923-blackstore-next-gate.md`, connector `meta.md` implementation line, UX plan, and UX `tasks.json` match the five-route dump. ADP-001 names PIC-001..008 only and does not claim PIC-009/L3 closed. Status stays `ready_for_sol_review`, not approved. `/sdd.finish` was not run.
5. `storeCoreContractBoundaryHasNoHttpOrFrameworkTypes` includes `application.storecore` and `application.dto.storecore`. The suite passed.

`application.yml` keeps `storecore.integration.enabled: false`. ADP-005+ remain `pending`.

## Required fixes

1. Rewrite the compatibility-lock paragraph in `sdd/wip/20260921-storecore-connector-adapter/2-technical/spec.md` so it matches the done lock: path, version, and sha256 constants, with config fail-close. Do not say that this task reads the YAML, requires `info.x-canonical`, or persists outbox/inbox evidence. Those stay later tasks. Point `TASK-ADP-002` GATE (`canonical fixture validation passes`) at the pin/config test, or drop the fixture claim. No YAML fixture was parsed.

## Residual NO-GO

Leave these undone. They are not defects in this slice:

- ADP-005+ live transport, TLS, bearer, Retry-After, outbox-before-HTTP, saga wiring, fixtures suite, rollout (ADP-006..010 and L3). Local/Testcontainers only if Sol's conditional gate still applies after this SDD correction.
- Fiscal/ARCA, MP-LIVE-05, live companion, secrets, tag, deploy, publish.
- Routes `/sesion`, `/caja/cierre`, `/ticket/:saleId`.
- Archive or `/sdd.finish`. `ready_for_sol_review` is not a Sol GO for live calls.
