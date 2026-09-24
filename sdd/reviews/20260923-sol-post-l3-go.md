| Item | Verdict | Scope / condition |
|---|---|---|
| Commit the BlackStore connector tranche (`TASK-ADP-005..010`, L3 evidence/reviews, and required connector SDD/code dependencies) | CONDITIONAL_GO | Stage by explicit path and inspect the staged diff. The current working tree also contains BSUX-ANG frontend changes and the connector history includes ADP-001..004; do not describe or commit the result as only ADP-005..010 unless the staged diff proves that claim. Exclude every StoreCore working-tree leftover. |
| Push the BlackStore feature branch and open a PR | CONDITIONAL_GO | Allowed with no tag, deploy, publish, live activation, or merge. The PR title/body must describe the complete `origin/master...HEAD` scope, including the already committed UX SDD and every newly committed tranche. |
| Merge the BlackStore PR | NO-GO | The existing r4 and L3 files explicitly are not PR/merge approvals. After the PR exists, two fresh Grok 4.7 reviews must independently read its title/body and SDD why, inspect the full `origin/master...HEAD` diff, run focused validations, and both record `APPROVED`. Any requested fix requires both reviews again. |
| BlackStore frontend `karma-jasmine` maintenance | GO | Test-tooling-only maintenance is authorized: add the minimum Angular-22-compatible Karma/Jasmine/Chrome test dependencies and lockfile changes needed for `npm test` to start, then run and record the suite. No route, product behavior, connector, or production dependency change. A started suite is not a pass unless all tests pass. |
| BSUX-ADP: Angular calls StoreCore HTTP on localhost | NO-GO | The approved local connector is BlackStore backend infrastructure, not a browser-to-StoreCore adapter. BSUX-ADP remains `blocked`; the browser must not receive service credentials or bypass BlackStore application ports. A future Angular-to-BlackStore-backend scope needs its own SDD/gate. |
| BSUX-ADP: Angular calls live StoreCore HTTP | NO-GO | No live companion, production host, identity, token, browser credential, operational traffic, or compatibility claim is authorized. |
| New POS-06/07/08 routes `/sesion`, `/caja/cierre`, `/ticket/:saleId` | NO-GO | The accepted frontend scope remains the five existing routes only. New routes, route guards, and workflows require a separate approved scope. |
| Existing BSUX-ANG five-route working-tree code | CONDITIONAL_GO | It may be packaged honestly as a separate commit or as an explicitly declared PR scope. Do not mix it invisibly into a connector-only commit, claim pixel completeness, or add routes. |
| StoreCore `EffectivePrice*.kt` leftovers | NO-GO | They are outside this tranche and have no new reviewed task/evidence here. Preserve them; do not stage, rewrite, delete, or use them to justify connector scope. |
| StoreCore `docs/agent/*` leftovers | NO-GO | They are outside this tranche. Preserve them and do not stage them into the BlackStore PR or treat the mirror as canonical evidence. |
| Fiscal / ARCA | NO-GO | No adapter, DDL, worker, payload, certificate, emission, secret, simulated-production path, or bypass behavior. |
| MP-LIVE-05 | NO-GO | No real payment, live account/credential, browser SDK activation, live webhook, or vendor activation. |
| Live companion / production canary / rollout | NO-GO | Local fixture, loopback, and Testcontainers evidence does not authorize a live host, production identity, traffic, compatibility claim, canary, migration, rollout, or operational rollback. |
| Real secrets / certificates / tokens | NO-GO | Only synthetic, non-reusable local material is allowed. No real value in source, config, CI, logs, evidence, or browser storage. |
| Tag / deploy / release / publish | NO-GO | Local validation and a PR do not constitute release authorization. |
| `/sdd.finish` / archive | NO-GO | Keep the connector and related WIPs open. Thirteen locally completed tasks do not close the live/release residuals and do not authorize moving them to `sdd/features/`. |
| Local connector validation and PR evidence refresh | GO | Luna may rerun backend connector/boundary suites, run the repaired frontend suite, inspect `git diff --check`, reconcile task/status text, and prepare truthful PR evidence. Keep defaults fail-closed and make no live calls. |

Overall: CONDITIONAL_GO

# Sol post-L3 next-development gate — StoreCore / BlackStore

**Fecha:** 2026-09-23

## Decision

The local connector implementation tranche is accepted as `already_done` at fixture/localhost/Testcontainers level: `TASK-ADP-001..010` and `TASK-ADP-L3-001..003` are 13 of 13 locally complete. Scope r4 and SDD r4 are both `APPROVED`; the architecture/resilience and security L3 reviews are also `APPROVED` for local evidence only. BlackStore's default `application.yml` remains fail-closed: integration disabled, fixture mode, transport and control kill switches on, empty endpoint/identity/token references, capability inactive, canary zero, and rollout disabled.

This gate authorizes packaging that work into scoped commits, pushing the existing BlackStore branch, opening an honest PR, and repairing the frontend test runner. It does not authorize merge yet. The four existing UX SDD commits are already part of `origin/master...HEAD`, while the working tree contains connector code/SDD plus separate BSUX-ANG frontend code. Staging and the PR description must reflect that actual shape; no connector-only claim may hide unrelated frontend changes.

The r4 and L3 approvals cannot substitute for the repository's mandatory PR reviews because each says it is not a GitHub PR review or merge approval. Once the PR exists, its complete branch diff requires two new independent Grok 4.7 `APPROVED` verdicts under the dual-review rule. GitHub jobs that do not start are not CI green.

## Verification record

- BlackStore is on `feature/blackstore-frontend-ux-system-v1`, with no current PR and uncommitted connector, frontend, and SDD changes.
- Connector `tasks.json` reports 13 of 13 done and keeps status `ready_for_sol_review`; it does not claim live approval.
- Frontend UX `tasks.json` keeps `BSUX-ADP` blocked and exposes only `/`, `/caja`, `/catalogo`, `/ticket`, and `/reportes`.
- A focused local backend connector/architecture/domain validation completed successfully for the reviewed tree.
- `npm test` was rechecked and did not start because `karma-jasmine` is absent. Therefore the BlackStore frontend suite is not green.
- The StoreCore `EffectivePrice*.kt` and `docs/agent/*` files are separate untracked leftovers and are not part of this authorization.

## Mandatory boundaries

- Single tenant means one merchant, one BlackStore runtime/database, and one separate StoreCore installation; no shared runtime, `store_id`, `store_hosts`, TenantFilter, or cross-database access.
- BlackStore USER is not StoreCore CUSTOMER.
- No invented HMAC or browser-held service bearer. The canonical server-side contract and its four-header identity remain the only connector contract.
- Intent/outbox remains durable before HTTP; recovery stays GET-first and bounded; 410 and unknown reconcile never authorize re-POST.
- Local rotation/canary/rollout rehearsal is evidence only. It is not permission to configure or execute those operations live.

## Residual NO-GO

Residual NO-GO is explicit: PR merge before fresh dual Grok PR approval; BSUX-ADP direct browser-to-StoreCore access on localhost or live; POS-06/07/08 routes; StoreCore EffectivePrice and `docs/agent` leftovers; fiscal/ARCA; MP-LIVE-05; live companion, host, identity, traffic, canary, rollout, or compatibility claim; real secrets/certificates/tokens; tag, deploy, release, publish; and `/sdd.finish` or archive.
