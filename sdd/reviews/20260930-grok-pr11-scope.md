VERDICT: APPROVED

Lane: SCOPE. Independent review of https://github.com/AgustinFalcon/blackstore/pull/11 (`feature/storecore-transport-coroutines` vs `origin/master`, HEAD `7a7502d091f2ade32d8d03112d0dcdb983e1c875`). Issue https://github.com/AgustinFalcon/blackstore/issues/10. This APPROVED is this lane only. It does not merge, tag, deploy, publish, approve on GitHub, or run `/sdd.finish`. Merge still needs the other PR #11 lane APPROVED.

## Why

PR title: "Dispatch StoreCore loopback HTTP on Kotlin coroutines." The body says `StoreCoreHttpTransport` sends and waits on `Dispatchers.IO` with `delay`, sync ports stay sync via `runBlocking` only at this edge, and fail-closed loopback is unchanged. It says this is not an ML `STOCK_DESIRED_CHANGED` dispatcher, and there is no live companion, fiscal work, or `sdd.finish`. The test plan leaves hosted CI unchecked and says it is not a pass.

Issue #10 asks for the same edge: IO and retry wait on `Dispatchers.IO`, default sleeper uses `delay`, sync ports stay sync, and existing loopback/fail-closed guards stay. Out of scope there: Mercado Libre `STOCK_DESIRED_CHANGED`, delivery `SENT`, OAuth, live BlackStore, browser credentials, and POS-06/07/08 routes.

## Validations run

`origin/master` is `885da7dab8c0c623b5940a1f52698d1373f89ac2` (merge of PR #8). `origin/master...HEAD` is one commit, 4 files, +28/−6:

- `backend/build.gradle.kts`
- `backend/src/main/kotlin/com/blackstore/infrastructure/storecore/StoreCoreHttpTransport.kt`
- `backend/src/test/kotlin/com/blackstore/connector/StoreCoreHttpTransportTest.kt`
- `sdd/STATUS.md`

No frontend, route, `application.yml`, Flyway, fiscal, GRANT, or workflow file is in the diff.

`git diff --check origin/master` and `git diff --check origin/master...HEAD` both exit 2 on `sdd/STATUS.md:3` (two trailing spaces on the updated `Validado` line). That line keeps the markdown hard break already on the replaced `2026-09-24` line. It does not add a forbidden surface.

From `backend`, `.\gradlew.bat test --tests com.blackstore.connector.StoreCoreHttpTransportTest --offline --rerun-tasks` exited 0. `:test` executed (6 actionable tasks, 6 executed). XML: 9 tests, 0 failures, 0 errors, 0 skipped.

`gh pr checks 11` reported no checks on `feature/storecore-transport-coroutines`. Absent checks are not CI green.

## Diff scope

`kotlinx-coroutines-core:1.8.1` is the only new dependency. The transport constructor takes `Dispatchers.IO`. The default sleeper is `runBlocking(io) { delay(ms) }` when the wait is positive. `client.send` moves into a private `send` that also uses `runBlocking(io)`. Public methods stay blocking. `StoreCoreDispatchGuard.assertCanDispatch` is still called before get, post, and reconcile post. The guard file is not in the diff: non-loopback plaintext stays rejected, and plain HTTP still needs loopback plus `allowPlainLoopback`.

`defaultCoroutineWaitHonorsZeroRetryAfter` builds the transport with the default sleeper, posts to the in-process server on `127.0.0.1`, and expects one retry (`Retry-After: 0`) then HTTP 200. The token in that test is still the synthetic `synthetic-not-reusable` string. No browser store is added.

`sdd/STATUS.md` records issue #10 as local coroutine dispatch, says it is not a Mercado Libre remote dispatcher, and keeps companion live, fiscal, MP-LIVE-05, and `/sdd.finish` as NO-GO. It says GitHub reported no checks and that is not CI green. `origin/master` at `885da7d` (PR #8) matches the merge history (PRs #1–#8). The WIP is not archived.

`AGENTS.md` is unchanged: a BlackStore USER is not a StoreCore CUSTOMER. This diff has no CUSTOMER identity, session, or route. The HTTP edge still sends the POS quadruple headers.

`backend/src/main/resources/application.yml` is outside the diff. On this tip it still has integration `enabled: false`, `mode: fixture`, and `allow-plain-loopback: false`. `frontend/src/app/app.routes.ts` is outside the diff and still only `''`, `caja`, `catalogo`, `ticket`, `reportes`, plus a wildcard back to `''`.

## What holds

- Retry wait and blocking send for this loopback client use `Dispatchers.IO` and `delay`.
- Sync callers stay sync. `runBlocking` is only on this infrastructure edge.
- Dispatch still goes through the existing fail-closed loopback guard. The new test talks to `127.0.0.1`.
- BlackStore USER is not turned into a StoreCore CUSTOMER.
- No live companion, Mercado Libre dispatcher or `SENT`, fiscal emission, browser credential, POS-06/07/08 route, or GRANT.
- The PR and STATUS deny CI green and `/sdd.finish`. GitHub checks did not run.

## Not granted

This file does not authorize merge by itself. Still NO-GO: live companion, Mercado Libre `STOCK_DESIRED_CHANGED` or delivery `SENT`, fiscal/ARCA, browser credentials, POS-06/07/08 routes, GRANT, tag, deploy, publish, calling hosted CI green, and `/sdd.finish`.
