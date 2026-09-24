VERDICT: CHANGES_REQUIRED

Lane: SCOPE/IMPL r2. Independent re-review of the BlackStore working tree on `feature/blackstore-frontend-ux-system-v1` after the five SDD-lane fixes. No fixes applied. Not a GitHub PR and not merge approval.

## Validations

`origin/master...HEAD` remains documentation only (`063476d`..`7e8338a`). The tranche is the uncommitted tree plus untracked connector Kotlin, tests, and `sdd/wip/20260921-storecore-connector-adapter/evidence/`.

Backend, `C:\Users\agustin\Desktop\BlackStore\backend`:

```text
.\gradlew.bat test --tests "com.blackstore.connector.StoreCoreRecoveryPolicyTest" --tests "com.blackstore.connector.StoreCoreEnvelopeValidatorTest" --tests "com.blackstore.architecture.ArchitectureBoundaryTest" --tests "com.blackstore.presentation.controller.CashAndSaleControllerTest" --tests "com.blackstore.domain.StoreCoreCanonicalContractTest"
```

Exit 0. `BUILD SUCCESSFUL` in 33s. Failures 0, errors 0.

| Suite | tests | failures |
|---|---|---|
| `StoreCoreRecoveryPolicyTest` | 6 | 0 |
| `StoreCoreEnvelopeValidatorTest` | 5 | 0 |
| `ArchitectureBoundaryTest` | 7 | 0 |
| `CashAndSaleControllerTest` | 5 | 0 |
| `StoreCoreCanonicalContractTest` | 2 | 0 |

SHA-256 of both canonical YAML copies is `7b907a2e11c52a66b7253407fb3f9450cae7b792beccf34c1636be9d3945de30`:

- `StoreCore/sdd/wip/20260921-storecore-pos-integration-contract-v1/2-technical/api/blackstore-integration.openapi.yaml`
- `StoreCore/backend/src/main/resources/openapi/blackstore-integration.openapi.yaml`

`StoreCoreCanonicalContract.SHA256` and `blackstore.storecore.contract.sha256` match that digest. `npm run build` was not run.

## Scope findings

The five applied fixes stay inside the local pin/ports/envelope slice.

- Recovery: `IDEMPOTENCY_PAYLOAD_MISMATCH` maps to `RETAIN_DURABLE_EVIDENCE`. Unnamed codes, including `UNKNOWN_CODE`, map to `NEVER_REPOST`. `GET_SAME_QUADRUPLE` is only `CONFLICT`. `OPERATION_RETIRED` stays `NEVER_REPOST`. `INSUFFICIENT_STOCK`, `CATALOG_VERSION_STALE`, and `VALIDATION` stay `NEW_OPERATION_SAME_SALE`. `retiredAndUnknownNeverRepost` asserts mismatch and `UNKNOWN_CODE` are not GET. `ReconcileProjection.unknownAuthorizesRepost()` and `StoreCoreRemoteEvidence.authorizesRepost()` remain constantly false. No re-POST path was added.
- Durable receipts: `StoreCoreOperationReceipt` rejects a null or blank `openapiDigestSha256` for `RESERVED`, `COMMITTED`, `RELEASED`, and `EXPIRED`. `durableReceiptRejectsMissingDigest` covers EXPIRED null and RESERVED blank. EXPIRED evidence still requires receipt, reservationRef, accepted price versions, digest, and reconciliation reason. PENDING still cannot carry a receipt.
- Config guard: `StoreCoreContractCompatibilityGuard` calls `assertCompatible` on configured path, version, and sha256 and throws `STORECORE_CONTRACT_INCOMPATIBLE` on drift. The unit test constructs the guard with the superseded digest and expects failure. `application.yml` keeps `integration.enabled: false` and `mode: fixture`. ADP-002 evidence and `tasks.json` AC-1 say YAML is not parsed and that headers, scopes, and `x-canonical` stay unpinned. No YAML parser was added in `main`.
- Named honesty edits landed: UX functional spec, UX plan, UX `tasks.json` (`gated_visual_dump` / `ux_ang_applied`), connector `meta.md` implementation line (`local_ports_and_pin`, not approved), `20260923-blackstore-next-gate.md` (five-route dump, no "No volcado Angular"), and ADP-001 evidence (PIC-009/L3 called residuals, not a BlackStore close). `ready_for_sol_review` remains. No `/sdd.finish`.
- ArchUnit `storeCoreContractBoundaryHasNoHttpOrFrameworkTypes` now includes `..application.storecore..` and `..application.dto.storecore..` plus the existing domain/application port packages. The rule passed. Those packages do not import Spring Web, `java.net.http`, OkHttp, or JDBC. `StoreCoreReceiptDto` stays in application.

Still absent, as required: `/sesion`, `/caja/cierre`, `/ticket/:saleId`. `frontend/src/app/app.routes.ts` is unchanged: `/`, `/caja`, `/catalogo`, `/ticket`, `/reportes`, plus `**` → ``. Frontend `src` has no match for those routes or `blackstore-integration`. Backend `src` has no `RestTemplate`, `WebClient`, `RestClient`, `java.net.http`, or OkHttp. `GET /api/v1/sales/{operationId}` still reads `LocalSaleSagaService.stored`. Cash close stays on `/api/v1/cash-sessions/{sessionId}/close`.

## Impl findings

Backend standards hold for this slice. Domain recovery types stay free of HTTP. The guard and envelope validator are Spring components in application; the ArchUnit ban is HTTP/JDBC, and that ban passed. Fixture durable receipts still stamp `"a".repeat(64)`, which satisfies the non-blank rule and is not a transport call. `StoreCoreRemoteEvidence` enforces digest on EXPIRED only; non-expired evidence constructed outside a durable receipt can still omit it. That does not authorize a call.

`actionForError` does not read `retryable`. Every `CONFLICT` becomes GET. That matches the requested matrix (GET only for that code) and is not a re-POST.

The code fixes do not add a route, client, secret, fiscal emitter, or live host.

## Required fixes

1. `sdd/wip/20260921-storecore-connector-adapter/2-technical/spec.md` still says the first executable task reads the canonical YAML, requires `info.x-canonical=true`, and persists the digest into outbox/inbox. This diff only replaced the old digest in that sentence. ADP-002 is done as a constant plus config fail-close and explicitly does not parse YAML. Rewrite that paragraph so headers, scopes, `x-canonical`, and outbox persistence are not described as already done. `StoreCoreContractRef` still says the digest is populated at runtime from the canonical YAML; that comment has to match the same narrowing.
2. `sdd/TRACEABILITY.md` still says "Angular volcado bloqueado" while STATUS item 3 and the UX WIP record the five-route dump. STATUS precedence item 2 still says the connector WIP is blocked pending StoreCore evidence and a Sol GO, while the gate section in the same file records ADP-001..004 done and ADP-005+ `CONDITIONAL_GO` for fixtures/localhost only. Make those two lines match the gate. Keep live companion, fiscal, and `/sdd.finish` unauthorized.
3. `sdd/wip/20260923-blackstore-frontend-ux-system-v1/2-technical/frontend-architecture.md` still says POS-07 `/caja/cierre` should be extracted in the dump. Sol NO-GO forbids that route. State that it stays embedded in `/caja` until a separate routes gate. Do not add the route.
