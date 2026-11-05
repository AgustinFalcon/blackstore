# PR #48 — security P3 corrections

Date: 2026-10-20. Validated source/test commit: `8f1edc0686f7087b7dcfc6ad7ab4b57af0c4823a`. Branch/worktree: `codex/split-blackstore-pr48`, `split-worktrees/blackstore-pr48`. Previous functional findings and their evidence remain historical. No push or publication.

## Corrections and reproductions

- Removed HTTP shutdown completely. Playwright global setup now launches the static server as a direct Node child (`process.execPath`, no shell), waits for its readiness marker and returns teardown that terminates only that owned ChildProcess/PID. Startup failure never terminates a process occupying the port. The server accepts only GET/HEAD; POST to the former shutdown path returns 405 for foreign Origin, same Origin and missing Origin. Browser tests verify the server still serves the app after every rejected attempt. After the complete run, a listener query reported `LISTENERS_AFTER_TEARDOWN=0` on port 4215. No token or HTTP teardown authority remains exposed.
- Scenario reset preserves the current demo role. Constructor defaults apply only to initial repository creation; loading any scenario cannot silently turn Cashier into Manager. Domain tests cover every scenario, retained role and denial of discount/product/stock management with exact unchanged snapshot. Browser resets to Closed under Cashier, verifies the shell role and a denied stock adjustment, then explicitly selects Manager for the separate allowed operation.
- Stock adjustment validates both delta and resulting quantity as safe integers, and requires the result to remain nonnegative before allocating IDs or publishing. Tests reject `MAX_SAFE_INTEGER` added to existing stock, allow an exact final `MAX_SAFE_INTEGER`, reject a further unit and unsafe negative delta without mutation, then return to zero. Movement IDs demonstrate no allocation during rejected attempts. Browser repeats overflow at stock 2, verifies stock/history remain unchanged, then records a valid +1 adjustment.

## Executed validation

- `npm run test:demo:domain`: **49 specs, 0 failures**.
- `npm run typecheck`, `npm run typecheck:demo`, `npm run typecheck:dct`: **PASS**. An initial test-source header-union type error was corrected before the passing final demo typecheck.
- `npm run build`: **PASS**.
- `CHROME_BIN=C:/Program Files/Google/Chrome/Application/chrome.exe npm test`: **293 SUCCESS**, Chrome Headless 155.0.0.0.
- `npm run test:demo`: **18/18 PASS**, Chromium, 22.9 seconds; all earlier E01/E11 and Astra cases plus both security reproductions.
- `git diff --check`: **PASS**. No backend, dependency/lockfile or operational-domain change.

Local artifacts remain `frontend/demo-test-report/index.html` and `frontend/demo-test-results/screenshots/`. Build/browser/Angular use the same permitted local process execution as earlier runs. The combined verification shell ended with a nonzero status because its final empty `Get-NetTCPConnection` lookup found no listener; browser and Angular themselves passed, and a separate explicit count confirmed zero listeners.

Independent review of this corrected source is **PENDING**. Prior acceptance limits remain unchanged: real PostgreSQL-backed SID metadata/touch/revocation, independent design, manual screen reader, automated contrast and 200% zoom are not claimed. These results cover the local frontend and its test harness only.
