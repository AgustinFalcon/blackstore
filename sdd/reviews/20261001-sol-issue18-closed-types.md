# Sol review — issue #18 closed POS wire types

**Date:** 2026-10-01

**Base:** `origin/master` at `8fedbeb`

**Scope:** Angular types and boundary mapping for the five existing master routes only.

## Verdict

**APPROVED** by three independent GPT-6.1-sol review lanes at medium effort after correction.

The implementation closes the finite wire vocabularies already consumed by `/`, `/caja`, `/catalogo`, `/ticket`, and `/reportes`: cash-session, sale and payment status; payment method; staff role; report formula and period; persistence mode; and the local commit/release action. Every type has a private constructor, static instances, an exact `fromWire`, and a fixed `Unknown` case. HTTP DTOs are translated by `PosWireMapper` before they enter signals or labels.

## Corrections required and resolved

1. `CASH` was initially absent from the second-payment selector. It is now selectable with `CARD`, `TRANSFER`, and `OTHER`; `Unknown` is excluded and the set is tested.
2. An unknown cash-session state initially allowed programmatic cash actions. The type now owns `canStartNewSession`; `Unknown` blocks open, close, and expense writes, while historical `RECONCILIATION_REQUIRED` preserves the backend rule that only an `OPEN` session prevents another opening. The component test verifies the neutral label and zero POSTs for all three actions.
3. Unknown sale and payment states map to neutral labels and never echo the raw wire.

## Validation

- `npm run build`: PASS on the corrected diff.
- `npx tsc -p tsconfig.spec.json --noEmit`: PASS on production and spec sources.
- `git diff --check`: PASS.
- `npm test -- --no-progress`: the local Windows runner did not reach Karma or any assertion. Angular's test builder failed while resolving its own absolute paths and `zone.js` with `Access denied / Cannot read directory ../../../../../../..`. This is not counted as a test pass or a code failure.

## Scope held

No backend, Flyway, StoreCore contract, capability, credentials, live/canary, fiscal, route, POS-06/07/08, PR #9 import, release, or `/sdd.finish` change. The existing reserve-to-payment orchestration is not redesigned by this types-only cut. Merge remains subject to the repository review gates; this Sol record does not impersonate or replace Grok approval.
