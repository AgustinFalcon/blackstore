| Item | Verdict | Scope / condition |
|---|---|---|
| StoreCore `TASK-PIC-009` + `TASK-L3-003` | already_done | Local evidence accepted; `BLACKSTORE_INTEGRATION` returned to `DISABLED`. This is not live readiness. |
| StoreCore `UX-ANG` on the 22 existing P/C/U routes | already_done | Code-scope closure only. DS tokens/layout are applied; no claim of pixel completeness or Stitch HTML parity for P-01/P-02/P-03. |
| BlackStore `BSUX-ANG` on `/`, `/caja`, `/catalogo`, `/ticket`, `/reportes` | already_done | Code-scope closure only. Five existing routes, simulator/local behavior retained; not pixel-complete or release-ready. |
| BlackStore `TASK-ADP-001..004` | already_done | Canonical digest re-pinned; contract-neutral ports, recovery policy, envelope validation, and DTO mapping remain transport-free. |
| BlackStore `TASK-ADP-005..010` + `TASK-ADP-L3-001..003` | CONDITIONAL_GO | Yes: Luna may proceed in dependency order, only against fixtures, mocks, localhost, or controlled ephemeral StoreCore/Testcontainers. No live companion or operational rollout. |
| Fiscal / ARCA | NO-GO | No code, DDL, worker, adapter, emission, certificates, secrets, or simulated-production behavior. |
| MP-LIVE-05 | NO-GO | No real payment, live account/credentials, browser SDK, live webhook, or activation. |
| Live StoreCore–BlackStore companion | NO-GO | No real host, identity, traffic, compatibility claim, canary, or production operation. |
| Real secrets / certificates / tokens | NO-GO | Synthetic, non-reusable test material only; no real value in repo, CI, logs, or evidence. |
| Tag / deploy / release / publish | NO-GO | Local validation is not release authorization. |
| `/sdd.finish` / archive | NO-GO | Keep WIPs open; do not move them to `sdd/features/`. |
| New POS-06/07/08 routes: `/sesion`, `/caja/cierre`, `/ticket/:saleId` | NO-GO | They require a separate routes/guards gate. |

# Sol next development gate — StoreCore / BlackStore

**Fecha:** 2026-09-23  
**Overall:** `CONDITIONAL_GO` for the next local connector implementation tranche only.

## Decision

Luna may start `TASK-ADP-005..010` and the three L3 reviews, following `tasks.json` dependencies. The authorization is limited to fixtures, mocks, localhost, synthetic identities/certificates/tokens, and controlled ephemeral StoreCore/Testcontainers.

The following constraints are mandatory:

- `blackstore.storecore.integration.enabled` and every new capability/kill-switch path remain fail-closed by default.
- ADP-005 may implement transport behavior only for local tests. No real endpoint, service identity, secret, certificate, or reusable credential is authorized.
- Intent/outbox must be durable before HTTP. Recovery is GET-first and bounded; `Retry-After` is honored. `410` and unknown reconcile never authorize re-POST.
- ADP-008/009/010 “rotation”, “canary”, “rollout”, and “rollback” mean local rehearsal only. They do not authorize an operational canary or live companion.
- L3 may review implementation and local evidence. L3-003 must preserve a disabled release gate; it cannot grant live, secret, deploy, publish, or archive approval.
- Any change to canonical path, version, digest, headers, envelope semantics, scopes, or error behavior stops transport work pending a new Sol review.
- No copied OpenAPI, direct StoreCore DB/DSN, shared runtime, `store_id`, invented HMAC, fiscal payload, payment payload, PAN, or unnecessary PII.

## Basis

The canonical YAML still hashes to:

`7b907a2e11c52a66b7253407fb3f9450cae7b792beccf34c1636be9d3945de30`

That value matches BlackStore's `StoreCoreCanonicalContract`, `application.yml`, technical spec, metadata, and ADP-001 evidence. ADP-001..004 establish the required local start gate: six application operations, read-only bounded reconcile, unknown-never-repost policy, state/error-code recovery, complete durable EXPIRED tuple requirements, status/envelope checks, and DTO-to-domain mapping without an HTTP client.

## UX closure

`UX-ANG` is closed as an honest bounded implementation: StoreCore retains the 22 existing routes and the current guards/USER-CUSTOMER separation; the token/layout changes compile and pass architecture, lint, unit, build, and focused accessibility checks. There is no Mercado Pago SDK import/dependency, `store_id`, or favoritos. The two `mercadopago.com.ar` occurrences are existing `checkoutUrl` test fixtures for the allowlisted redirect contract, not an SDK or proof of payment. P-01/P-02/P-03 have no archived Stitch HTML, so no pixel-complete claim is accepted.

`BSUX-ANG` is closed for the five existing routes only. `app.routes.ts` contains `/`, `/caja`, `/catalogo`, `/ticket`, and `/reportes`, plus wildcard redirect; it contains no `/sesion`, `/caja/cierre`, or `/ticket/:saleId`. The frontend contains no `/blackstore-integration` call and retains explicit simulator/local messaging. This is not pixel-complete and does not close the frontend release gate.

BlackStore's Angular production build passes. Its configured `npm test` cannot start because `karma-jasmine` is absent from the installed/project test dependencies. That tooling gap must be fixed under a separately authorized maintenance scope before claiming a green BlackStore frontend test suite; it does not reopen the bounded five-route visual code scope and is not a release pass.

## Validation record

- BlackStore backend focused command requested by Sol: `BUILD SUCCESSFUL`:
  - `StoreCoreRecoveryPolicyTest`
  - `StoreCoreEnvelopeValidatorTest`
  - `ArchitectureBoundaryTest`
- BlackStore backend full `.\gradlew.bat test`: `BUILD SUCCESSFUL`.
- Canonical YAML SHA-256 recomputation: exact match with `7b907a2e11c52a66b7253407fb3f9450cae7b792beccf34c1636be9d3945de30`.
- BlackStore `frontend/src` grep for `/blackstore-integration` and `/sesion`: no matches.
- BlackStore forbidden route grep for `/sesion`, `/caja/cierre`, and `/ticket/:saleId`: no matches.
- StoreCore `frontend/src` grep for Mercado Pago SDK imports/scripts, `store_id`, and favoritos: no matches.
- StoreCore frontend:
  - architecture scan passed;
  - lint passed;
  - 21 test files / 48 tests passed;
  - production build passed;
  - Playwright/axe focused accessibility suite: 5 passed.
- BlackStore frontend:
  - production build passed;
  - test runner did not start: missing `karma-jasmine`; no green test claim.
- `git diff --check` for both frontend scopes: passed.

## Residual NO-GO

Fiscal, MP-LIVE-05, live companion operation, real secrets/certificates/tokens, production identity/host, operational canary, POS-06/07/08 routes, tag, deploy, release, publish, and `/sdd.finish` remain explicitly unauthorized. Local/Testcontainers success must not be represented as live compatibility, production readiness, CI green, or permission to archive.
