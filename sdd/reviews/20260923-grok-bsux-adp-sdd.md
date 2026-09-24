VERDICT: CHANGES_REQUIRED

Independent SDD lane for the BlackStore uncommitted tranche (BSUX-ANG on the five existing routes, plus TASK-ADP-001..004). Not a GitHub PR review and not merge approval. No fixes applied.

## Why

The allowed slice is present and the hard NO-GO lines are intact: visual chrome stays on `/`, `/caja`, `/catalogo`, `/ticket`, `/reportes`; canonical path/version/SHA-256 match the current StoreCore YAML; reconcile unknown does not authorize re-POST; domain and application connector types do not import an HTTP client. That is not enough to approve the tranche as recorded.

`StoreCoreRecoveryPolicy.actionForError` sends `IDEMPOTENCY_PAYLOAD_MISMATCH` and every unnamed code down `else -> GET_SAME_QUADRUPLE`. The connector technical spec says mismatch retains original evidence and does not mutate; a corrected sale gets a new id. GET-first is the timeout/uncertain path, not the default. ADP-005 would inherit that matrix.

The SDD record contradicts the diff. Tasks mark BSUX-ANG and ADP-001..004 done, while the UX functional spec, connector meta, and `sdd/reviews/20260923-blackstore-next-gate.md` still say there is no Angular dump, code is not authorized, and the next step is still to wait for those gates. ADP-001 evidence also says StoreCore PIC-009/L3 are closed locally. The Sol gate this evidence cites still has PIC-009 and L3-003 as conditional, not closed.

## Validations run

From `C:\Users\agustin\Desktop\BlackStore\backend`:

```text
.\gradlew.bat test --tests "com.blackstore.connector.StoreCoreRecoveryPolicyTest" --tests "com.blackstore.connector.StoreCoreEnvelopeValidatorTest" --tests "com.blackstore.architecture.ArchitectureBoundaryTest" --tests "com.blackstore.domain.StoreCoreCanonicalContractTest"
```

BUILD SUCCESSFUL. 19 tests, 0 failures, 0 errors:

| Suite | tests | failures |
|---|---|---|
| `StoreCoreCanonicalContractTest` | 2 | 0 |
| `StoreCoreEnvelopeValidatorTest` | 5 | 0 |
| `StoreCoreRecoveryPolicyTest` | 5 | 0 |
| `ArchitectureBoundaryTest` | 7 | 0 |

`StoreCoreCanonicalContract.SHA256` is `7b907a2e11c52a66b7253407fb3f9450cae7b792beccf34c1636be9d3945de30`. `Get-FileHash -Algorithm SHA256` on both canonical copies returned the same digest (upper case):

- `StoreCore/sdd/wip/20260921-storecore-pos-integration-contract-v1/2-technical/api/blackstore-integration.openapi.yaml`
- `StoreCore/backend/src/main/resources/openapi/blackstore-integration.openapi.yaml`

That YAML has `info.version` `1.0.0-draft` and `x-canonical: true`. BlackStore code does not read the file.

Grep `frontend/src` for `blackstore-integration`, `/sesion`, `/caja/cierre`, `ticket/:`: no matches. `app.routes.ts` is only ``, `caja`, `catalogo`, `ticket`, `reportes`.

Grep `backend/src/main` for `RestTemplate`, `WebClient`, `java.net.http`, `OkHttpClient`, `HttpClient(`: no matches. No `store_id`, Mercado Pago SDK, or new fiscal emitter in this diff. `application.yml` `sha256` is the contract digest, not a secret, and no Kotlin code reads that property.

`origin/master...HEAD` is UX documentation only (STATUS, backlog, traceability, next-gate, and `20260923-blackstore-frontend-ux-system-v1`). The Angular dump and ADP code are uncommitted.

## SDD honesty gaps

- `20260923-blackstore-frontend-ux-system-v1/1-functional/spec.md` still says "Sin código Angular nuevo" and "Implementación Angular: otro GO." The working tree marks BSUX-ANG done.
- `sdd/reviews/20260923-blackstore-next-gate.md` says "**No** volcado Angular" and its next steps still wait for a BSUX-ANG GO and for TASK-ADP-001. The same file's table already records CONDITIONAL_GO. `sdd/STATUS.md` is the honest line; this review file is not.
- UX `tasks.json` keeps `execution_strategy: docs_only` and `status: documented` after Angular edits. `3-tasks/plan.md` says the connector is "ADP-003 ports only". This tranche also pins the contract, adds recovery, and validates the envelope/DTO.
- Connector `meta.md` still says `implementation: blocked_by_sol_gate_and_storecore_readiness` and "it does not authorize code yet" while `tasks.json` marks ADP-001..004 done. `ready_for_sol_review` / not approved remains correct. The implementation line does not.
- `evidence/TASK-ADP-001-start-gate.md` says "PIC-009/L3 also closed locally." Sol `20260923-sol-remaining-gates.md` still treats PIC-009 as the residual to implement and L3-003 as not started until that is green.
- ADP-002 is `done` with evidence pointing only at `StoreCoreCanonicalContract.kt`. `assertCompatible` is called from the unit test, not from dispatch. Configured `sha256` is unused. Headers and scopes are not pinned. The task AC that says the YAML is read, `x-canonical` checked, and incompatible input rejected before dispatch is ahead of the code.
- `retiredAndUnknownNeverRepost` does not assert an unknown `errorCode`. It checks `OPERATION_RETIRED`, `CONFLICT`, and `INSUFFICIENT_STOCK` only. Unknown reconcile is covered separately and does not authorize re-POST.

## Architecture gaps

Backend standards: domain connector types are pure Kotlin. `StoreCoreContractFacade` delegates to domain ports and is not an HTTP adapter. `StoreCoreReceiptDto.toDomain` stays in application. `unknownAuthorizesRepost()` and `authorizesRepost()` are constantly false. Envelope checks require the six fields, HTTP status equal to `code`, success null error fields, and `OPERATION_RETIRED` with `retryable=false`. Branching uses `errorCode`, not `message`.

Gaps:

- Recovery else-branch invents GET for outcomes the spec does not send to GET. `CONFLICT -> GET_SAME_QUADRUPLE` is acceptable as GET-first and must not become a blind re-POST in ADP-005. Mismatch and the default are not.
- `StoreCoreOperationReceipt` requires receipt, reservationRef, and non-empty `acceptedPriceVersions` for RESERVED/COMMITTED/RELEASED/EXPIRED, and forbids them on PENDING. It does not require `contract.openapiDigestSha256`. The spec durable tuple includes the digest. Fixture receipts still stamp `"a".repeat(64)`, not the pinned digest. `LocalSaleSagaService` still substitutes `"b".repeat(64)` when digest is null.
- `storeCoreContractBoundaryHasNoHttpOrFrameworkTypes` does not cover `application.storecore` or `application.dto.storecore`, which is where the new policy and DTO live. `storeCorePortsLiveInDomainOnly` only sees names ending in `Port`, so `StoreCoreContractOperations` is invisible to it. Domain ports themselves are still under `domain.port.out`.
- `CatalogResponse.items` is domain `CatalogItem`. Presentation already exposes `SaleStatus` the same way. Warn, not a transport leak.
- `StoreCoreEnvelopeValidator` remains a Spring `@Component` in application. Pre-existing. New code adds no HTTP type there.
- Frontend: HttpClient stays in the five pilot components. NgRx and OnPush are not required for this tranche. No new route, no `/blackstore-integration/v1`, no payment SDK. Close stays on `/caja` (Sol forbids extracting `/caja/cierre`). Banner is integration-blocked / backend-down, not a separate entitlement state. Ticket does not render the original/effective/discount snapshot. Task text already says not pixel-complete. `CatalogSalePolicy` does not read `items`, so the fixture SKU line does not change sale rules. `GET /api/v1/sales/{operationId}` reads `LocalSaleSagaService.stored` in-process; it is not a StoreCore client.

## Required fixes

1. `StoreCoreRecoveryPolicy.actionForError`: map `IDEMPOTENCY_PAYLOAD_MISMATCH` to retain-original / never re-POST, not `GET_SAME_QUADRUPLE`. Do not default unnamed codes to GET. GET remains for uncertain delivery and retryable `CONFLICT` only. Add a test that mismatch and an unknown code do not return `GET_SAME_QUADRUPLE` and do not authorize re-POST.
2. Durable `StoreCoreOperationReceipt` states must reject a null or blank `openapiDigestSha256`, including EXPIRED.
3. Fail closed when configured `blackstore.storecore.contract` path, version, or sha256 disagrees with `StoreCoreCanonicalContract`. Record that ADP-002 did not parse the YAML: headers, scopes, and `x-canonical` stay unpinned until a later task. Do not leave ADP-002 `done` against AC-1 as written unless that narrower evidence is what the task now claims.
4. Correct the SDD text so it matches the diff: UX functional spec, `20260923-blackstore-next-gate.md` (drop "No volcado Angular" and the stale next steps), connector `meta.md` implementation line, UX plan/tasks strategy, and ADP-001 evidence (delete the PIC-009/L3-closed claim). Keep `ready_for_sol_review`, not approved, and do not run `/sdd.finish`.
5. Extend the HTTP/framework ArchUnit ban to `application.storecore` and `application.dto.storecore`.

## Residual NO-GO

Leave these undone. They are not defects in this slice and they are not approval to continue:

- ADP-005+ transport, TLS, bearer, Retry-After, outbox-before-HTTP, saga wiring, fixtures suite, rollout (ADP-006..010 and L3).
- Fiscal/ARCA, MP-LIVE-05, live companion, secrets, tag, deploy, publish.
- Routes `/sesion`, `/caja/cierre`, `/ticket/:saleId`.
- StoreCore PIC-009 and L3-003.
- Archive or production-ready. `ready_for_sol_review` is not a Sol GO for live calls.
