# PR #48 — reversal stock boundary correction

Date: 2026-10-20. Validated source/test commit: `c20d3bc0f00a7bfa9dd40d2c00683fe20e4a64f4`. Branch/worktree: `codex/split-blackstore-pr48`, `split-worktrees/blackstore-pr48`. No push or publication.

`StockRestorationPlan` is a pure domain step that prepares all restored quantities before reversal builds any event, allocates an ID or publishes a snapshot. It aggregates quantities per product, requires positive safe line quantities and safe sums, requires the affected product/current stock to exist within bounds, and validates every resulting stock as a safe nonnegative integer. Only after the complete plan succeeds does the repository construct the sale, stock events and cash refund as one snapshot.

The exact reproduction adjusts p1 by `Number.MAX_SAFE_INTEGER - 2`, then attempts reverse of seeded V-90. The reversal rejects; the snapshot reference and publication count remain unchanged. An explicit -1 adjustment receives movement ID 102, proving the rejection did not consume IDs, and the subsequent valid reversal restores exactly MAX_SAFE_INTEGER.

A multiline cash sale exercises a valid first restoration followed by overflow on its second product. Failure preserves the entire snapshot, both stocks, original completed sale, expected cash and publication count. Recovery receives the next expected event ID 106; the valid reversal then restores both products and compensates cash. The browser repeats the seeded overflow, verifies retained completed receipt/stock and absent reversal history, then makes explicit room and completes the reversal.

Executed final gates: **51/51 domain specs**, **295/295 Angular specs** (Chrome Headless 155.0.0.0), **19/19 Chromium browser cases** (24.4 seconds), application/spec/demo/DCT **typechecks PASS**, production **build PASS**, and **diff-check PASS**. Commands are the existing `test:demo:domain`, `test`, `test:demo`, `typecheck`, `typecheck:demo`, `typecheck:dct`, and `build` scripts. The complete browser suite retains all previous E01/E11, functional and security reproductions; artifacts remain in `frontend/demo-test-report/` and `frontend/demo-test-results/`.

Independent exact-head re-review is pending; previous approvals are not inherited. The earlier documented real-SID/PostgreSQL, design/accessibility and production-integration limits remain unchanged. No backend, dependency/lockfile or operational feature was changed.
