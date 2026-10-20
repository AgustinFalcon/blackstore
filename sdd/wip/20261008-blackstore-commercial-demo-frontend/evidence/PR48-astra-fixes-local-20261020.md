# PR #48 — Astra finding corrections

Date: 2026-10-20. Validated source/test commit: `c4131efd6b86754028b39e6dc5cc211b6f4dc8ca`. Branch/worktree: `codex/split-blackstore-pr48`, `split-worktrees/blackstore-pr48`. This increment follows initial transplant `0d363d8`; [earlier evidence](PR48-transplant-local-20261020.md) remains historical. No push or publication.

## Four corrections

- Cash declaration: `declared` initializes in ARS by converting expected cents once (`expectedCash / 100`); confirmation still converts the form input back to cents. The browser keeps the seeded default of 62,500 ARS, leaves the discrepancy reason blank and successfully closes with zero difference.
- Calendar: pure `DemoCalendar.BuenosAires` owns timezone, displayed timestamps and business-day identity. `DemoDateRange` owns validated inclusive date bounds and a fail-closed Unknown range; input translation stays in `DemoWireMapper.period`. Sales and expense queries use the same range policy, and the UI/receipt/export use the same calendar. Unit tests exercise 02:59:59Z versus 03:00:00Z. A UTC browser creates sales and expenses on opposite sides of Buenos Aires midnight, checks rendered dates, each day's totals, filtered sales CSV contents and report CSV contents.
- Cart recovery: quantity zero removes an existing line before checking current product sellability. Positive quantities still require a sellable product and valid stock; checkout still rejects a stale/inactive line. Unit/browser reproductions deactivate a product already in the cart, verify denied increase/checkout without mutation, then remove that line individually.
- Seed chronology: seeded cash movements are ordered from old to new, with an opening preceding each historical cash sale and a distinct current opening before today's sale. Current-shift queries/expected cash start at that final opening; V-93/V-96 no longer inflate today's expected cash. Tests assert chronological ordering, opening boundaries, the current V-90 movement, and agreement between expected and declared cash.

## Executed validation

- `npm run test:demo:domain`: **47 specs, 0 failures**.
- `npm run typecheck`: **PASS** (app and Jasmine specs).
- `npm run typecheck:demo`: **PASS**.
- `npm run typecheck:dct`: **PASS**.
- `npm run build`: **PASS**, final Angular production bundle.
- `CHROME_BIN=C:/Program Files/Google/Chrome/Application/chrome.exe npm test`: **291 SUCCESS**, Chrome Headless 155.0.0.0.
- `npm run test:demo`: **16/16 PASS**, Chromium, 20.1 seconds. Includes all earlier E01/E11/browser regression plus the three new Astra reproductions. The midnight test explicitly uses a UTC browser context while the business calendar remains Buenos Aires.
- `git diff --check`: **PASS**. No changes to backend, operational core/cash/sales, DCT harness, CLR or POSC WIPs.

Local artifacts remain `frontend/demo-test-report/index.html` and `frontend/demo-test-results/screenshots/`. Build/browser/Angular used the same permitted local process execution as the earlier run. No dependency/lockfile change.

## Review and acceptance scope

Astra's findings on the prior source are addressed; independent review of this corrected source is **PENDING**, and no approval is inherited. The prior E01 limitation is unchanged: no PostgreSQL-backed SID metadata/touch/revocation execution; the frontend boundary uses sentinel cookies and intercepted session responses. Independent design, manual screen reader, automated contrast, 200% zoom and untested viewport gates remain pending. This increment claims local frontend corrections and regression results only.
