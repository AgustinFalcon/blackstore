# PR #48 — isolated transplant and local validation

Date: 2026-10-20. Validated implementation/test source: `500c9fa53b33e736661215766f3ce7fb924a6ac0`. Local branch: `codex/split-blackstore-pr48`; dedicated worktree: `split-worktrees/blackstore-pr48`. No push, deployment, tag or publication.

## Provenance and protected ancestry

Parent: final PR #47 `05fbf923d7ee0e9a0181f6b737e865dac2072d36`. Only the three implementation commits above old design `4d2c9cd` were cherry-picked: `6d64e8f` → `4c60024`, `3dbc52a` → `4cb9b4a`, `f52dac45` → `868b413`. Backup branch `backup/pr48-before-transplant-20261020` retains old head `f52dac45cac6accf61f289a904552752793e2208`. The original demo worktree and its dirty WIP files were preserved.

Corrections are commits `186f6df` (surface/bootstrap), `6284d06` (closed permissions/domain), and `500c9fa` (browser gates). The diff from the parent is empty for backend, operational core/cash/sales features, DCT browser harness, POSC WIP and CLR WIP. The parent's `ChromeHeadlessNoSandbox` test command remains intact. The historical implementation evidence describes the old source and grants no approval to this head.

## Boundary and domain changes

`main.ts` evaluates pure `AppSurface` before dynamically importing one bootstrap. Demo installs only its own root, router, memory repository and local assets. Operational initializer, `SessionStore`, health, HTTP interceptor and recovery belong to the operational import graph. Unknown paths bootstrap a neutral chooser without either graph. Exact decoded path segments select demo; `/demografia`, encoded slashes and malformed paths do not select an operational fallback.

Cross-surface links are ordinary document anchors. The returning document executes the unchanged session bootstrap/guard; demo profile never supplies operational authority. No demo code reads/writes cookies, local/sessionStorage or IndexedDB, or imports an operational service.

`DemoAction`, `DemoPermissionDecision` and `DemoPermissionPolicy` implement the final #47 action matrix independently of operational RBAC. Unknown role/action denies. A neutral role may explicitly select a known profile to recover; an unknown target profile is rejected without mutation. Each repository command checks current policy synchronously before changing state, sequence, receipt or event. Checkout revalidates a pending discount; switching to Cajero retains the cart/discount and denies payment until an explicit authorized recovery. Cashier movements, close and opening are permitted by demo policy. Manager-only product changes, adjustments and reversals remain denied after a form loses permission.

Product status is also a closed type, including Unknown; labels/toggle transition live in that type. External conversions are centralized in `DemoWireMapper`. In-memory fixtures are constructed directly with canonical domain instances rather than raw wire states. Page identity and report rules live in pure domain objects. Discounted category amounts allocate integer cents and reconcile to the receipt; unsafe monetary totals fail before commit. CSV quotes fields and neutralizes formulas behind whitespace/control prefixes.

## Executed gates

- `npm run test:demo:domain`: **42 specs, 0 failures**. Includes explicit role/action matrix, every unknown action/role, denied commands without publication/ID allocation, pending form permission loss, discount retention/recovery, cashier cash controls, all type mappings/Unknown, product transitions, report rounding and CSV injection.
- `npm run typecheck`: **PASS** (application and all Jasmine specs).
- `npm run typecheck:demo`: **PASS** (demo browser source/config).
- `npm run typecheck:dct`: **PASS** (preserved operational browser source).
- `npm run build`: **PASS**, Angular production bundles. Entry chooses the surface before loading its bootstrap.
- `CHROME_BIN=C:/Program Files/Google/Chrome/Application/chrome.exe npm test`: **286 SUCCESS**, Chrome Headless 155.0.0.0. All inherited frontend regression specs are included.
- `npm run test:demo`: **13/13 PASS**, bundled Chromium, 2026-10-20. Final run duration 17.7 seconds. Local HTML report: `frontend/demo-test-report/index.html`; screenshots: `frontend/demo-test-results/screenshots/`.
- `git diff --check`: **PASS**. Protected-path comparison against `05fbf923` is empty.

Build/Chrome used normal unsandboxed local process execution after esbuild directory resolution was denied inside the filesystem sandbox. No dependency versions or lockfile were changed. Frontend dependencies are reused through a local ignored node_modules junction.

## E01 and E11 evidence scope

The E01 cold-load test records requests before bootstrap and cold-loads/reloads all 11 demo paths under absent/valid-labelled/expired-labelled HttpOnly SID sentinels with API requests aborted. It asserts zero API initiations, no operational identity/logout, identical cookies, retained local/session journal sentinels, zero Storage method calls and zero IndexedDB opens from demo. `/demografia` stays neutral. These labels are test fixtures, not authenticated sessions verified against PostgreSQL.

The cross-document E01 test uses intercepted session/CSRF responses: valid operational identity loads, entering demo destroys the old document, and returning after simulated expiration performs a new real frontend session bootstrap. An operational `/caja` entry redirects through its unchanged guard to `/sesion`. This validates frontend integration with the session boundary, not server-side SID metadata. A real PostgreSQL-backed SID valid/expired/touch/revocation check is **NOT_RUN**; E01 remains partial against the full acceptance wording.

E11 domain coverage exercises all 16 known actions against both known roles and Unknown. Browser coverage changes role on payment after discount, verifies denial and retained line, restores manager and confirms the original sale once; opens a product form, changes role before confirm, verifies denial and unchanged product; and verifies cashier expense/close. Permission denial returns typed decisions without business mutation. Additional domain tests cover pending reverse/stock adjustment and all unknown-role mutations.

The original ten browser cases still cover routes at 1440/768/390, multiline cash/card/retry/reversal, CRUD, current-shift cash movements, filtered exports, printing invocation, focus/error recovery, Escape/focus return, mobile drawer tab containment and quick cart. Screenshots of mobile home were visually inspected after adding the shared profile chooser; no overflow or clipped control was observed.

## Remaining gates and limits

Independent exact-head functional/security reviews and independent design approval are **NOT_RUN**. Manual screen reader, automated contrast/axe audit, 360/1024/1280 coverage and 200% zoom are **NOT_RUN**. Existing keyboard/responsive tests provide partial E08 coverage; E07/E08 are not full acceptance. Print tests invoke/stub printing and inspect print media; they do not certify a physical printer.

No real StoreCore, Mercado Libre, fiscal integration, production security, backend acceptance, POSC/CLR completion or homologation is claimed. Backend was unchanged, so backend/PostgreSQL/DCT execution was not repeated for this frontend-only transplant. Review must address this source and the final evidence commit; old approvals do not transfer.
