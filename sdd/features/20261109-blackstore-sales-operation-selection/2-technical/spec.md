# Technical specification

## Design

- Add `OperationId`, an immutable UUID value object with one `fromWire` translator at the route boundary. It is distinct from opaque `SaleRouteId`.
- Add closed `SaleOperationSelection` (`None`, selected identity, `Unknown`) and `SaleSelectionPhase` (`Idle`, `Loading`, `Ready`, `Unavailable`, `Unknown`) types with private constructors, labels/rules and tests. Views/stores never compare phase strings.
- `queryParamMap.getAll('operationId')` is translated once. Missing means None; exactly one valid UUID means selected; duplicates/malformed mean Unknown and cause no HTTP.
- The component only navigates. One route subscription calls the store, preventing handler-plus-subscription double loads. Closing removes `operationId` while preserving unrelated query parameters.
- Add store APIs `selectOperation(OperationId)`, `retrySelection()`, `refreshSelection()`, `rejectSelection()`, `clearSelection()` and `leaveSelection()`. Every detail publisher shares one publication generation, while mutation admission retains its own generation. Selection A→B, legacy-open→selection, close and route exit invalidate stale publications without cancelling an already admitted journaled mutation.
- Keep journaled mutation `busy` separate from selection-read phase. Existing `open(string)` remains compatible for the POS continuation route and does not become URL selection authority.
- Selection errors retain operation identity and set `Unavailable`; Ready requires mapper correlation to the requested operation. `can` also refuses any detail that does not match the selected `OperationId`. Session change clears selection and invalidates list/detail/command generations.
- Component-local command drafts and confirmation reviews reset on selection identity/phase-to-idle changes. Confirmation still rechecks exact detail identity and permission.
- Use existing `SurfaceDataSource` as the closed list availability signal so unavailable and verified-empty are mutually exclusive. Do not create fixture fallback.
- Capture validation uses `TicketMoney` and current pending amount before invoking the existing command. Backend revalidation remains mandatory.

## Security and architecture

- Existing `sessionGuard`, `StaffPermission.SaleRead`, ownership filters, action permissions, CSRF and journal recovery remain unchanged.
- URL input can identify only a read attempt; it never manufactures ticket identity or authorization evidence.
- No new writer or backend route. Navigation/retry evidence must prove zero POST.
- Finite state lives in closed types. Domain does not import Router, HTTP or component code.

## Verification

- Domain unit tests for valid/None/Unknown selection and every phase.
- Store tests for fresh select, malformed rejection, A→B, close/session invalidation, failed read/retry and zero POST.
- Component tests for route-driven selection, URL close, draft/review reset, list unavailable versus empty, capture validation and focus targets.
- Browser controlled-HTTP tests for reload/back/forward, close during delayed reads, keyboard/focus, live completion status, 44 px targets and 320/390/1440 reflow.
- Real DCT acceptance remains bounded to existing PostgreSQL/JAR/Angular infrastructure; no positive `route.fulfill` may be presented as real backend proof.
- Full frontend typecheck, lint, unit tests, build and relevant browser suites; independent product/UX, technical and security reviews before merge.
