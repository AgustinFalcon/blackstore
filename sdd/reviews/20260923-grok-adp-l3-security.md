VERDICT: APPROVED

Lane: L3 SECURITY + RELEASE GATE (`TASK-ADP-L3-003`). Independent local review of BlackStore `feature/blackstore-frontend-ux-system-v1` at HEAD `7e8338a` plus uncommitted ADP-005..010. Not a GitHub merge review and not merge approval. No commit, push, tag, deploy, publish, or `/sdd.finish`.

This APPROVED is local evidence only. It does not grant live, secret, deploy, publish, or archive approval. Those remain NO-GO.

## Why

Sol `sdd/reviews/20260923-sol-next-dev-go.md` is `CONDITIONAL_GO` for local ADP-005..010 and the three L3 reviews, limited to fixtures, mocks, localhost, and controlled ephemeral StoreCore/Testcontainers. The same file's residual list stays NO-GO: fiscal/ARCA, MP-LIVE-05, live StoreCore–BlackStore companion, real secrets/certificates/tokens, operational canary, POS-06/07/08 routes, tag, deploy, release, publish, and `/sdd.finish`. This review records that existing decision. It does not invent a live GO.

`tasks.json` still marks `TASK-ADP-L3-001` and `TASK-ADP-L3-002` `pending`. At write time `sdd/reviews/` had no L3 architecture or resilience verdict. Their gates ("architecture review has no P0", "resilience review has no P0") are not accepted here. A green security check cannot open the release gate while those gates are unrecorded. Scope r4 and SDD r4 approved the ADP-005..010 implementation text; both say they do not close L3.

AC-1 holds for this tree: fixtures, telemetry, audit, and redacted persistence do not carry a secret, PAN, fiscal document, or customer PII. AC-2 holds: default composition cannot dispatch live HTTP. The Sol GO/NO-GO gate is the conditional local GO plus the residual NO-GO list above.

## Fail-closed defaults

`backend/src/main/resources/application.yml`:

| Key | Value |
|---|---|
| `blackstore.storecore.integration.enabled` | `false` |
| `blackstore.storecore.integration.mode` | `fixture` |
| `blackstore.storecore.transport.kill-switch` | `true` |
| `blackstore.storecore.transport.tls-required` | `true` |
| `blackstore.storecore.transport.allow-plain-loopback` | `false` |
| `blackstore.storecore.transport.base-url` | empty |
| `blackstore.storecore.transport.identity-ref` / `token-ref` | empty |
| `blackstore.storecore.control.capability-active` | `false` |
| `blackstore.storecore.control.kill-switch` | `true` |
| `blackstore.storecore.control.identity-ref` / `token-ref` | empty |
| `blackstore.storecore.control.canary-percent` | `0` |
| `blackstore.storecore.control.rollout-enabled` | `false` |
| `blackstore.storecore.contract.sha256` | `7b907a2e11c52a66b7253407fb3f9450cae7b792beccf34c1636be9d3945de30` |

Recomputed SHA-256 of StoreCore `sdd/wip/20260921-storecore-pos-integration-contract-v1/2-technical/api/blackstore-integration.openapi.yaml` is that same digest. `StoreCoreCanonicalContract.SHA256` matches it. BlackStore has no OpenAPI copy: no `*openapi*` yaml/yml in this repo. The canonical file stays in StoreCore.

Spring reads `integration.enabled` (health only, default false) and `integration.mode`. Mode `fixture` selects `FixtureStoreCoreInventoryAdapter` (`matchIfMissing = true`). `StoreCoreHttpTransport` and `TransportStoreCoreInventoryAdapter` have no Spring stereotype and no `@Bean`. `StoreCoreControlPlane` is not a bean. Its constructor defaults are capability off, kill-switch on, empty refs, canary 0, rollout off. Flipping the unbound transport/control YAML keys does not create a live client.

`StoreCoreDispatchGuard.assertCanDispatch` blocks when integration is disabled, the kill-switch is on, capability is inactive, identity or token ref is blank, or the base URL is blank. Plaintext is rejected when TLS is required, and also when the host is not loopback or plain loopback is not allowed. `assertLocalDispatchAllowed` throws `STORECORE_LIVE_ROLLOUT_FORBIDDEN` when rollout is on or canary is above 0. Rollback forces kill-switch on, capability off, canary 0, and rollout off.

## Secrets, PAN, fiscal, PII

- Reservation DTO is catalog version, variant id, quantity, and expected price version. No payment or fiscal body.
- Fixtures under `backend/src/test/resources/storecore-fixtures/` are envelope JSON: synthetic trace ids, `SKU-1` / `Cafe`, receipt and reservation refs. No PAN, CVV, card number, fiscal document, email, phone, Bearer token, or private key.
- Telemetry logs kind, digest prefix, outcome, retries, operation id, and identity ref. `StoreCoreTransportTelemetry.containsSecret` treats `bearer`, `authorization`, and the token value as secret. `telemetryNeverIncludesBearerOrToken` uses `synthetic-not-reusable` and expects that value absent. The token is resolved at call time and is not a log field.
- Rotation audit stores actor, event, reason, and identity ref. It does not store the token ref. `rotate` rejects a blank ref and a ref that contains `Bearer`. Tests use `id-ref` / `tok-ref`.
- Inbox/outbox `payload_redacted` is `{"kind":...}` or `{"state":...,"kind":...}`. Payments persist method, amount, fee, and status, not a PAN.
- `FiscalBoundaryPolicy` refuses a production commit when fiscal status is `NOT_CONFIGURED` or no valid external authorization is present. Companion environment in default YAML is `TEST`. This tranche has no fiscal adapter, certificate, or emission.
- Frontend `app.routes.ts` is `/`, `/caja`, `/catalogo`, `/ticket`, `/reportes`, and wildcard redirect to `/`. No `/sesion`, `/caja/cierre`, `/ticket/:saleId`, or `/blackstore-integration` in `frontend/src`.
- No `.env`, PEM, `AKIA`, or `sk_live` material. HTTP tests pass the literal `synthetic-not-reusable` into a `127.0.0.1` server.

`application-local.yml` has username and password `blackstore` for `jdbc:postgresql://localhost:5433/blackstore`. That is a local profile database password, not a StoreCore token, PAN, certificate, or fiscal secret. It does not authorize live use, deploy, or publish.

## Validations run

From `C:\Users\agustin\Desktop\BlackStore\backend`:

```text
.\gradlew.bat test --tests "com.blackstore.connector.StoreCoreControlPlaneTest" --tests "com.blackstore.connector.StoreCoreHttpTransportTest" --tests "com.blackstore.connector.StoreCoreRecoveryRehearsalTest" --tests "com.blackstore.architecture.ArchitectureBoundaryTest" --no-daemon
```

BUILD SUCCESSFUL. Local Gradle only. GitHub CI was not run and is not claimed green. XML timestamps `2026-09-24T00:46:42Z` through `2026-09-24T00:46:55Z`. 19 tests, 0 failures, 0 errors, 0 skipped.

| Suite | tests | failures |
|---|---|---|
| `StoreCoreControlPlaneTest` | 3 | 0 |
| `StoreCoreHttpTransportTest` | 8 | 0 |
| `StoreCoreRecoveryRehearsalTest` | 1 | 0 |
| `ArchitectureBoundaryTest` | 7 | 0 |

`ArchitectureBoundaryTest` passing is local boundary evidence for this command. It is not an L3-001 verdict and it does not clear that task's P0 gate.

## Residual NO-GO

Leave these unauthorized. This file does not lift them:

- Live StoreCore–BlackStore companion, production host, operational canary, and rollout.
- Real secrets, certificates, and tokens.
- Fiscal/ARCA and MP-LIVE-05.
- POS-06/07/08 routes: `/sesion`, `/caja/cierre`, `/ticket/:saleId`.
- Tag, deploy, release, and publish.
- `/sdd.finish` and moving WIPs to `sdd/features/`.
- `TASK-ADP-L3-001` and `TASK-ADP-L3-002` until their own reviews record no P0. This file does not mark `TASK-ADP-L3-003` done in `tasks.json`.
