VERDICT: APPROVED

Lane: SCOPE. Independent review of https://github.com/AgustinFalcon/blackstore/pull/8 (`feature/blackstore-catalog-price-columns` vs `origin/master`, commit `7a94ec2780d6f9c10eb7a7c12e30fa08a86dc416`). This file does not merge, tag, deploy, publish, or run `/sdd.finish`. GitHub reported no checks on this branch; that is not CI green.

## Why

PR title: "Show catalog price version and keep reports non-fiscal." The body says the catalog table shows variant, price version, and unit price from the BlackStore API. When the projection has no price version, the screen says the cashier enters it. Shift and day reports say they are not a fiscal result when the flag is false. The browser still does not call StoreCore.

`sdd/STATUS.md` on this commit is unchanged. It still says the browser does not call StoreCore, a normal StoreCore start leaves `BLACKSTORE_INTEGRATION` disabled, and fiscal, companion live, MP-LIVE-05, and `/sdd.finish` are NO-GO. The WIP stays open.

## Validations run

`git diff origin/master...7a94ec2`: 1 commit (`7a94ec2`), 2 files, +12/−2. Paths:

- `frontend/src/app/features/catalog/catalog-panel.component.ts`
- `frontend/src/app/features/reports/shift-report.component.ts`

`frontend/src/app/app.routes.ts`, `frontend/src/app/core/api.ts`, `backend/src/main/resources/application.yml`, and `sdd/STATUS.md` are not in the diff. `git diff origin/master 7a94ec2` on those four paths is empty. No backend, migration, profile, secret, tag, or deploy file changed.

`API_BASE` at this commit is still `http://localhost:8081/api/v1`. Frontend `src` has no `localhost:8080`, `Authorization`, `ARCA`, or `/sdd.finish`.

From `frontend`, `npm run build` (`ng build`) exited 0. Bundle generation completed at 2026-09-25T04:06:47.348Z. Initial total 370.30 kB (estimated transfer 98.49 kB). Output stayed under `frontend/dist`, which `frontend/.gitignore` ignores. The worktree source stayed at `7a94ec2`.

`gh pr checks 8` reported no checks on `feature/blackstore-catalog-price-columns`. The commit status API returned `state=pending` with `total_count=0`. Check-runs for `7a94ec2` returned `total_count=0`. This repo has no `.github/workflows`. Absent checks are not CI green. There is no billing job on this SHA that started and then failed a step; that absence is not a code failure.

## Diff scope

Catalog still loads with `GET ${API_BASE}/catalog`. The row type now reads `priceVersion` and `unitPrice`. Those names are already on BlackStore `CatalogItem`, and `CatalogController` returns `snapshot.items` inside `CatalogResponse`. The table adds "Versión de precio" and "Precio unitario". A missing price version (`null` or `''`) renders `la carga el cajero`. A null unit price renders `—`. A present version and a numeric price, including `0`, render the API values. The fixture row `SKU-1` is built without those fields, so they stay null and the screen takes the cashier / dash path. A loopback snapshot that already parsed `priceVersion` and `unitPrice` would show them through this same BlackStore response. The component does not open another host.

Reports still load `GET ${API_BASE}/reports/shift` and `GET ${API_BASE}/reports/daily`. `fiscalLabel(false)` returns `no es resultado fiscal` on both the shift line and the day line. `fiscalLabel(true)` returns `dato marcado, sin emisión` and performs no request. `ReportProjection.fiscalResult` defaults to `false`. `ReportFormulas.contribution` does not set it. `CounterApplicationService` copies `projection.fiscalResult` into the shift and day responses. `CashAndSaleControllerTest` already expects `$.data.fiscalResult` false on both report routes. The lede still says the projection is not a fiscal result and not free cash.

`app.routes.ts` is still `''`, `caja`, `catalogo`, `ticket`, `reportes`, and a wildcard redirect to `''`.

## What holds

- The catalog table shows price version and unit price from `GET /api/v1/catalog` on the BlackStore API.
- A projection with no price version tells the cashier to enter it, and a null unit price is a dash.
- A false `fiscalResult` is labeled `no es resultado fiscal` on shift and day.
- A true flag is a label only (`dato marcado, sin emisión`). This diff adds no fiscal emission.
- Routes, `API_BASE`, and `application.yml` are outside the diff. The browser still does not call StoreCore.
- No secret, tag, deploy, or `/sdd.finish` is in the change.
- GitHub checks did not run. That is not CI green and it is not a product-code failure.

## Not granted

This APPROVED is this lane's scope verdict only. Merge stays with the other fresh Grok 4.7 PR review. Still NO-GO: a new route, a browser call to StoreCore, activating `BLACKSTORE_INTEGRATION`, companion live, fiscal/ARCA emission, MP-LIVE-05, tag, deploy, publish, and `/sdd.finish`.
