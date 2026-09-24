VERDICT: APPROVED

Lane: SCOPE/IMPL. Independent review of the BlackStore working tree on `feature/blackstore-frontend-ux-system-v1` (not a GitHub PR). No fixes applied.

## Validations

`origin/master...HEAD` is four documentation commits (`063476d`..`7e8338a`). No application code. The tranche under review is the uncommitted working tree plus the listed untracked Kotlin, tests, and `sdd/wip/20260921-storecore-connector-adapter/evidence/`. StoreCore EffectivePrice leftovers were ignored.

Backend, `C:\Users\agustin\Desktop\BlackStore\backend`:

```text
.\gradlew.bat test --tests "com.blackstore.connector.StoreCoreRecoveryPolicyTest" --tests "com.blackstore.connector.StoreCoreEnvelopeValidatorTest" --tests "com.blackstore.architecture.ArchitectureBoundaryTest" --tests "com.blackstore.presentation.controller.CashAndSaleControllerTest"
```

Exit 0. `BUILD SUCCESSFUL` in 17s. Suite reports: `StoreCoreRecoveryPolicyTest` 5, `StoreCoreEnvelopeValidatorTest` 5, `ArchitectureBoundaryTest` 7, `CashAndSaleControllerTest` 5. Failures 0, errors 0.

SHA-256 of both canonical YAML copies (SDD path and `StoreCore/backend/src/main/resources/openapi/blackstore-integration.openapi.yaml`) is `7b907a2e11c52a66b7253407fb3f9450cae7b792beccf34c1636be9d3945de30`.

`npm run build` was not run.

## Scope findings

- BSUX did not add `/sesion`, `/caja/cierre`, or `/ticket/:saleId`. `frontend/src/app/app.routes.ts` is unchanged: `/`, `/caja`, `/catalogo`, `/ticket`, `/reportes`, plus `**` → ``. Rail and home links use only those five routes. POS-06/07/08 stay documented and unrouted.
- BSUX did not change cash, ticket, reserve, commit, release, reversal, or report business rules. `LocalSaleSagaService` and the cash/payment/report services are untouched. Reserve, payment, split, commit, release, and reversal request bodies are the same. UI changes are chrome, tokens, loading/empty/error/disabled/success presentation, `errorCode`-first copy, a local numeric keypad, and a read of the in-process saga. New `GET /api/v1/sales/{operationId}` calls `LocalSaleSagaService.stored` (memory lookup). Missing keys return envelope `NOT_FOUND` with `retryable=false`. It does not call `/blackstore-integration/v1`. Catalog fixture lines (`SKU-1` / `Cafe` / `variant-1`) are display data on the existing read-only snapshot; sale blocking is still the previous stale/missing snapshot policy.
- ADP-003/004 do not leak HTTP types into the domain. `StoreCoreReceiptDto` lives in `application/dto/storecore` and imports no Spring Web, `java.net.http`, OkHttp, or JDBC. Domain ports, `StoreCoreRecoveryAction`, `StoreCoreRemoteEvidence`, and `StoreCoreReconcilePort` are contract-neutral. `ArchitectureBoundaryTest.storeCoreContractBoundaryHasNoHttpOrFrameworkTypes` passed. Reconcile unknown does not authorize re-POST: `ReconcileProjection.unknownAuthorizesRepost()` is constantly `false`, `StoreCoreRemoteEvidence.authorizesRepost()` is constantly `false`, the fixture reconcile only splits present vs unknown receipts, and the blocked adapter throws `BlockedStoreCoreIntegrationException`.
- PENDING cannot invent a receipt. `StoreCoreOperationReceipt` and `StoreCoreRemoteEvidence` reject a PENDING receipt or `reservationRef`. Recovery maps PENDING to `RETAIN_PENDING`. EXPIRED requires a non-blank receipt, non-blank `reservationRef`, non-empty `acceptedPriceVersions`, and, on `StoreCoreRemoteEvidence`, a non-blank `reconciliationReason`. `StoreCoreRecoveryPolicy.evidenceFrom` sets `EXPIRED remote tuple requires reconciliation` and `RECORD_RECONCILIATION_REQUIRED`. `StoreCoreContractRef` already requires a non-blank `contractVersion`. `openapiDigestSha256` is copied through and is still nullable; see impl findings. It is not a re-POST path.
- No live StoreCore host, secret, StoreCore DSN, invented HMAC, `channel=POS`, or `SALE` on `EXTERNAL_BLACKSTORE`. `application.yml` keeps `storecore.integration.enabled: false` and `mode: fixture`. The only new config key is the digest pin. No `WebClient`, `RestClient`, `java.net.http`, or OkHttp client was added. Frontend `HttpClient` stays on `http://localhost:8081/api/v1` (existing pilot pattern). Inter is loaded from the Google Fonts CDN; that is not a StoreCore endpoint.
- Digest pin is current: `StoreCoreCanonicalContract.SHA256` and `blackstore.storecore.contract.sha256` are `7b907a2e11c52a66b7253407fb3f9450cae7b792beccf34c1636be9d3945de30`. `assertCompatible` rejects the superseded digest `aba69747…9bda` and version `1.0.1`. Transport is absent. `TASK-ADP-005`..`010` and the three L3 tasks remain `pending`. No HTTP adapter class exists.

## Impl findings

Backend standards: domain stays free of Spring/JPA. Ports are interfaces. `StoreCoreContractFacade` is a plain delegator, not a Spring adapter and not wired as a client. `CatalogResponse.items` reuses domain `CatalogItem`; acceptable for this pilot and not an HTTP leak. Envelope checks still branch on `errorCode`. `requireStatusMatchesCode` enforces HTTP status equals `code`. Messages are not recovery inputs (`StoreCoreEnvelopeValidatorTest.errorCodeNotMessageDrivesRecovery`).

`actionForError` maps `OPERATION_RETIRED` to `NEVER_REPOST`, `CONFLICT` to `GET_SAME_QUADRUPLE`, and `INSUFFICIENT_STOCK` / `CATALOG_VERSION_STALE` / `VALIDATION` to `NEW_OPERATION_SAME_SALE`. Any other code, including `IDEMPOTENCY_PAYLOAD_MISMATCH`, also becomes `GET_SAME_QUADRUPLE`. That is not a re-POST. Durable mismatch handling stays with pending ADP-006.

EXPIRED digest nullability is a residual, not a hole that authorizes a call. The receipt and evidence constructors enforce the rest of the durable tuple and the reconciliation reason. Digest enforcement belongs with ADP-006 persistence; there is no transport to dispatch.

ADP-002 is a constant lock plus `assertCompatible`. Nothing in `main` re-hashes the YAML, and `contract.sha256` is not injected. The constant matches both YAML files hashed in this review. That is enough for the pre-transport pin. It is not a client.

Frontend standards: pilot `HttpClient` inside the five existing components is the accepted baseline. No new StoreCore integration HTTP was added from the UI. No NgRx migration was required for this gate. States covered on the five routes: shell loading / blocked banner / backend-down, cash loading / empty / mutation error+Reintentar / open disabled while `OPEN`, catalog loading / empty / stale copy / error+Reintentar, ticket local status line, reports loading / unknown margin / error+Reintentar. POS-01 does not disable the ticket shortcut when no caja is `OPEN`; the route was already reachable and the forbidden session route was not invented to police it. A failed `GET /cash-sessions` clears loading without setting `error()`. Neither changes posted business rules.

`sdd/reviews/20260923-blackstore-next-gate.md` still says “No volcado Angular” and lists BSUX-ANG as future work. `sdd/STATUS.md` and the BSUX task record the five-route dump as done. The code matches STATUS, not that stale paragraph.

## Required fixes

None.
