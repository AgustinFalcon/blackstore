VERDICT: APPROVED

Lane: implementation scope. Independent review of https://github.com/AgustinFalcon/blackstore/pull/9 (`feature/blackstore-pos-counter` vs `origin/integration/blackstore`, HEAD `f28ea4648bd7ceffc92c3782da3d5376614b47f2`). This file does not merge, approve on GitHub, push, commit, tag, deploy, or publish. Gradle was not run.

## Why

PR title: "Record counter sales and show the eight POS screens." The body targets `integration/blackstore` and keeps a live connector and fiscal emission out. A counter sale is recorded when fiscal emission is not configured. `GET /api/v1/sales/{operationId}` reads the sale back after a process restart. The eight counter routes talk only to BlackStore, statuses are closed types, and the report says contribución, turno, and día. HTTP login, a StoreCore connector, CAE/PDF, and a merge to master stay out.

## Validations run

`git diff origin/integration/blackstore...HEAD`: 2 commits (`300ce14`, `f28ea46`), 76 files, +3624/−1067. Frontend `src/app` is 55 of those files, +3222/−1016. The eight route containers replace the old cash, catalog, ticket, report, and shell components.

From `C:\Users\agustin\Desktop\BlackStore\frontend`:

```text
npx ng test --watch=false --browsers=ChromeHeadless
```

Exit 0. Chrome Headless 153.0.0.0 (Windows 10): Executed 7 of 7 SUCCESS (0.156 secs / 0.139 secs). TOTAL: 7 SUCCESS. Bundle generation completed at 2026-09-29T18:27:02.685Z. Specs: `app.component.spec.ts` (2), `sale-block.spec.ts` (4), `closed-status.spec.ts` (1).

## Scope checklist

- **container → view → store → use case → HTTP.** Each of the eight routes is a container that binds a view and calls a use case. Views take state inputs and emit outputs. They do not inject the store or `HttpClient`. Use cases call `BlackstoreApiService` and write `PosStore`. That service is the only POS HTTP client, and every path is under `API_BASE`. The shell banner is the exception: `AppComponent` calls the existing `HealthApiService` and writes the same store. That call is `GET ${API_BASE}/health`, not a screen use case.
- **Closed statuses.** `StaffRole`, `CashSessionStatus`, `SaleStatus`, `PaymentStatus`, `ReportPeriod`, `ReportFormula`, and `PosPaymentMethod` use a private constructor, static instances, `fromWire`, and `Unknown`. `fromWire` runs in the API mapper, the session store, and the ticket payment select.
- **Views do not compare status strings.** Cash uses `status.open` / `status.closed`. Ticket and ticket-read print `status.label` and `payment.status.collected` / `method.label`. Reports print `formulaName.label` and `periodKind.label`. `dialog() === 'commit'` and the other dialog checks are local dialog modes, not sale, cash, or payment codes. The ticket container compares `status === SaleStatus.Reserved` by identity.
- **Roles.** Known roles are `CASHIER`, `SUPERVISOR`, `OWNER`, and `AUDITOR`. Guards are session, cash, sell, reports, and close, derived from those capabilities. Auditor has reports only. Cashier has cash and sell, not reports or arqueo.
- **No CUSTOMER guards.** `frontend/src` has no CUSTOMER guard, route, or role.
- **No Mercado Pago SDK.** `frontend/package.json` has no Mercado Pago dependency. Payment methods are `CASH`, `CARD`, `TRANSFER`, and `OTHER`.
- **API base.** `frontend/src/app/core/api.ts` is `http://localhost:8081/api/v1`. Health and the POS client use that constant. The actor interceptor adds `X-Actor-Id` and `X-Role` only when the URL starts with that base. No `/blackstore-integration/v1` call.
- **Dialogs match `dialogs.md`.** Close, expense, reverse, release, commit, stock, stale, and fiscal in the cash, cash-close, and ticket views use the table title, body, primary, and cancel. StoreCore bloqueado and Entitlement disabled in the shell match DLG-SC-BLOCKED and DLG-KILL. Reverse and close are danger. Stock includes `availableQuantity`.
- **ReportFormula.** `ReportFormula.Contribution` is code `CONTRIBUTION` and label `contribución`. The reports view renders `formulaName.label` with `periodKind.label` for turno and día. The closed-status spec asserts that label.
- **POS-06 has no HTTP login.** `/sesion` writes the actor in `sessionStorage` and navigates home. The view states there is no HTTP login and that the console sends `X-Actor-Id` and `X-Role`. No login POST.

The fiscal change matches the body: `FiscalBoundaryPolicy` no longer blocks a recorded sale, and the V4 trigger functions return `NEW` without emitting. `stored` falls back to the recorded sale for GET after restart. Confirm and release still go through the in-memory saga service. Backend tests were left to the other lane.

## Not granted

This APPROVED is this lane’s scope verdict only. Merge stays closed until the other fresh Grok 4.7 review is also APPROVED. Still out: live StoreCore connector, CAE or PDF, HTTP login, Mercado Pago in the browser, merge to master, tag, deploy, and publish.
