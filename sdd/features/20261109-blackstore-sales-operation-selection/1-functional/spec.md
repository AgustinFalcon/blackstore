# Functional specification

## Problem

On a fresh visit to `/ventas`, authorized staff can list sales but cannot select a row to open the already implemented operations panel. The panel can appear only from incidental singleton state left by another route, so the visible selection and the URL are not authoritative.

## Requirements

- BSOS-F01 — Every visible sale exposes an explicit native action named “Ver operaciones”. No sale is auto-selected and no detail inherited from another route is presented without a matching URL selection.
- BSOS-F02 — `/ventas?operationId=<UUID>` is the single source of selected-operation intent. Fresh load, reload, back and forward reconstruct the read with GET. The operation need not remain in the current page/filter. Closing removes only that query parameter.
- BSOS-F03 — Selecting, retrying and closing never reserve, capture, confirm, release or reverse. Visibility, ownership, allowed actions and current evidence remain server authority.
- BSOS-F04 — No selection, loading, ready, unavailable and invalid selection are explicit closed states. Loading/error preserve the requested identity. A newer selection supersedes an older read; close, route exit or session change prevents a late response from publishing.
- BSOS-F05 — Selection changes clear amount, method, reason and confirmation reviews. Invalid capture input is explained before any command; no draft or confirmation crosses operation identity.
- BSOS-F06 — List loading, verified empty and unavailable are distinct. A failed list never simultaneously claims that there are no sales.
- BSOS-F07 — The selector and panel are keyboard operable and named. Explicit selection moves focus to the panel heading once; completion is announced without stealing focus again. Closing returns focus to the originating selector when present, otherwise to the list heading. Verify 320/390/1440 without horizontal overflow and with 44 px targets.
- BSOS-F08 — Sales/detail provenance remains BlackStore HTTP/PostgreSQL authority. Historical product provenance remains Unknown when absent; no demo or inferred date/source enters the productive surface.

## Acceptance scenarios

1. From a fresh `/ventas` load with two distinguishable sales, selecting B updates the URL and performs exactly one GET for B, then renders B’s panel.
2. Reload and browser back/forward reproduce the URL selection without POST or automatic selection.
3. Selecting A then B before A returns never renders A; closing during a read also prevents publication.
4. A failed GET preserves the selected operation and offers “Reintentar venta” and “Cerrar”; retry requests exactly the same identity.
5. A malformed or duplicated `operationId` performs no HTTP and renders a fail-closed invalid-selection state.
6. Filter/pagination changes do not clear the selected operation. Logout clears selection/detail and late reads remain ignored.
7. Changing/closing selection resets all command drafts and confirmation dialogs; capture zero, malformed or above pending is disabled with guidance.
8. List failure renders unavailable/retry, not the verified-empty message.
9. Mouse, Enter and keyboard traversal work at 320, 390 and 1440 px with stable focus and no overflow.
