# Commercial demo frontend — local implementation evidence

Base: `4d2c9cd`. Branch: `feat/commercial-demo-frontend`. Worktree: `work/blackstore-demo-frontend`. No backend changes, push, PR, merge or publication.

## Implemented surface

Explicit lazy `/demo` composition root owns a memory repository. Deep links bypass real session bootstrap and backend health; existing authenticated routes/HTTP clients/guards/recovery remain independent. Reload replaces demo state. Navigation shares a single snapshot. No demo IndexedDB/localStorage, credentials or business HTTP. Fonts use the system stack so the presentation works without Google requests.

| Route | Actions and visible outcomes | Evidence |
| --- | --- | --- |
| `/demo` | Summary, activity receipt links, sale/catalog/customer/cash shortcuts | Responsive route smoke |
| `/demo/pos` | Search/category/stock filters, product detail, add, quantities/remove, customer selection, authorized discount, confirmed clear, continue | Multiline E2E and domain invariants |
| `/demo/pago` | Explicit cash/card/transfer, received/change, reference, cancel/back preserving cart, confirm/retry | Cash/card/cancel/retry E2E; transfer domain method |
| `/demo/ventas/:id` | Snapshot receipt, download, print, new sale, confirmed reasoned reversal; missing sale recovery | Receipt/reversal/download E2E; print invocation stub only |
| `/demo/ventas` | Search, date/status/payment filters, clear, pagination/detail, filtered CSV | Search/pagination/export E2E; date/type filters implemented |
| `/demo/productos` | Search/category, detail, create/edit, activation and validation | CRUD/edit/activation E2E |
| `/demo/inventario` | Stock filters, history drawer, signed reasoned adjustment | Adjustment/reversal/history E2E |
| `/demo/clientes` | Search, create/edit, purchase history, select for sale, occasional customer | CRUD/edit/history/select E2E |
| `/demo/caja` | Open, income/expense, reason filter, count/difference, confirmed close, print | Expense/close/open E2E; income/validation domain |
| `/demo/reportes` | Dates, net sales/expenses/payment/category/product tables, empty, filtered export | Future empty period/clear/export E2E |
| `/demo/escenarios` | Normal/closed/no-sales/low-stock/recoverable-error/slow fixtures, confirmed reset, fictitious manager/cashier | Closed/error reset E2E; role/domain tests |

All controls invoke a navigation, query, validation or mutation. Permission-denied commands produce explanations. Snapshots preserve previous sale prices/names; local reversal creates compensating stock/cash entries and retains the original sale. Integer minor units, idempotent command receipt lookup, atomic publication and domain checkout validation steps protect cross-screen consistency.

## Executed checks

- `npm run typecheck`: PASS.
- `npm run build -- --preserve-symlinks`: PASS, optimized lazy demo bundle. Temporary drive T removed after build.
- `npm run test:demo:domain`: 13 specifications, 0 failures, PASS via Jasmine's actual core running pure-domain/repository specs in Node. This is separate from Karma.
- Playwright Chromium: 6 test cases reported PASS. Route sweep: 11 routes × desktop 1440, tablet 768 and mobile 390; no business HTTP requests and no page horizontal overflow. Sale/receipt/reversal, catalogue/customer CRUD, adjustment, activation, retry/cancel/card, downloads, report empty/filter, cash close/open, sales pagination and mobile drawer asserted.
- Screenshots: 18 images under ignored `frontend/demo-test-results/screenshots/`; home, POS, payment, inventory, reports and receipt at three widths. Desktop POS image visually inspected locally.
- Playwright webServer teardown does not finish in this Windows tool environment after all six cases print PASS. Execution explicitly interrupted with Ctrl+C; this limitation is retained rather than claiming a clean runner exit. Static server is scoped to 127.0.0.1:4215 and stopped by the interruption. A subsequent Node TCP probe returned `STOPPED: no listener on 4215`.
- Karma Angular test runner: NOT_RUN to assertions. Compilation attempts, including temporary drive/preserve-symlinks, fail reading sandbox-denied `C:/Users/agustin`. Domain specs run separately as described above.

## Limits

Independent reviews, hosted CI and browser acceptance of production/backend routes are NOT_RUN for this implementation. Hardware printing/system print dialog, screen reader, exhaustive keyboard/contrast audit and all scenario permutations are NOT_RUN. CSV/receipt browser downloads were exercised. The demo surface is complete but this evidence does not assert exhaustive 100% validation of every possible input or production capability.
