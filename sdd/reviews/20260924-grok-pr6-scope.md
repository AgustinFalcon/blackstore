VERDICT: APPROVED

Lane: SCOPE. Independent review of https://github.com/AgustinFalcon/blackstore/pull/6 (`chore/blackstore-record-pr5` vs `origin/master`, commit `f9d58a21c58118c0afbe09ea9c6afacbbce20fd3`). This file does not merge, tag, deploy, publish, or run `/sdd.finish`. GitHub reported no checks on this branch; that is not CI green.

## Why

PR title: "Record PR 5 and block a sale when the local catalog is closed." The body says a local HTTP stub can feed a reserve with the catalog variant and price version, and that a closed stub makes the catalog read unavailable so the sale stays blocked. It records PR #5 on master. It says this is not a connection of the two processes, that StoreCore stays disabled on a normal start, and that default `application.yml` is unchanged.

`sdd/STATUS.md` on this commit records PR #5 as merged at `c5f6239`, which is the merge commit on `origin/master`. It says a normal StoreCore start leaves the module `DISABLED`, so the two processes are not connected. Companion live stays unapproved. It says GitHub reported no checks and that this is not CI green. It says there is no tag and no publication, release stays disabled, and fiscal, companion live, MP-LIVE-05, and `/sdd.finish` stay NO-GO. The WIP stays open.

## Validations run

`git diff origin/master...f9d58a2`: 1 commit (`f9d58a2`), 3 files, +118/−4. Paths:

- `backend/src/main/kotlin/com/blackstore/infrastructure/storecore/LoopbackCatalogAdapter.kt`
- `backend/src/test/kotlin/com/blackstore/connector/LoopbackCatalogHttpTest.kt`
- `sdd/STATUS.md`

`backend/src/main/resources/application.yml` is not in the diff. `git diff origin/master f9d58a2 -- backend/src/main/resources/application.yml` is empty. `application-loopback.yml` is not in the diff. No frontend, migration, profile, or secret file changed.

At `f9d58a2`, `application.yml` still has `blackstore.storecore.integration.enabled: false`, `mode: fixture`, `transport.kill-switch: true`, `allow-plain-loopback: false`, and an empty `base-url`. There is no `spring.profiles.active`. Token and identity refs stay empty. `LoopbackCatalogAdapter` is still a bean only when `blackstore.storecore.integration.mode=loopback`.

StoreCore sibling migrations, unchanged by this PR: `V5__blackstore_integration_registry.sql` inserts `BLACKSTORE_INTEGRATION` with state `DISABLED`. `V7__blackstore_future_optional_promotion.sql` requires that state to stay `DISABLED` and raises if the module is activated. No later StoreCore migration names the module.

Focused test from `backend`, `.\gradlew.bat test --tests com.blackstore.connector.LoopbackCatalogHttpTest --offline`:

- `LoopbackCatalogHttpTest`: 1 test, 0 failures, 0 errors, 0 skipped

`BUILD SUCCESSFUL`. `gh pr checks 6` reported no checks on `chore/blackstore-record-pr5`.

## Diff scope

`LoopbackCatalogAdapter.currentSnapshot` still reads `GET {canonicalPath}/catalog` through the guarded transport. `BlockedStoreCoreIntegrationException` still becomes a null snapshot. `InterruptedException` now restores the interrupt flag and returns null. Any other `Exception`, including the checked `IOException` from `HttpClient.send` when the socket is closed, returns null. A non-200 or a blank body still returns null. Parsing stays outside that catch.

`HttpClient.send` throws `IOException` and `InterruptedException`, which are not `RuntimeException`. The previous `catch (_: RuntimeException)` left a closed stub as a thrown connection error. The widened catch is what turns that closed stub into an unavailable snapshot.

`LocalSaleSagaService.beginReserve` is unchanged. A null snapshot makes `CatalogReserveLinePolicy.resolve` return the cashier lines, then `CatalogSalePolicy.assertSaleAllowed` throws `ForbiddenOperationException` ("catalog is unavailable; sale transition is blocked and catalog stays read-only") before `callReserve`. `GlobalExceptionHandler` still maps that exception to HTTP 403 `FORBIDDEN` with `retryable=false`.

`LoopbackCatalogHttpTest` binds `127.0.0.1` on an ephemeral port and serves one active catalog row (`variantId` 9, `priceVersion` `pv-9`). `CatalogReserveLinePolicy` rewrites the reserve line to variant `9` and price version `pv-9`. After `server.stop(0)`, a new adapter's `currentSnapshot()` is null, and `CatalogSalePolicy.assertSaleAllowed` throws `ForbiddenOperationException`. The test constructs transport settings with `enabled=true` and `allowPlainLoopback=true` only in memory. The token is the existing local constant `local-loopback`. The test does not start StoreCore.

## What holds

- A local stub on `127.0.0.1` supplies the catalog variant and price version used for the reserve line.
- A closed stub returns a null snapshot, and the sale policy blocks the transition before inventory reserve.
- `application.yml` is outside the diff and stays fixture and fail-closed. A normal BlackStore start does not select the loopback catalog bean.
- STATUS records merged PR #5 at `c5f6239` and says the two processes are not connected.
- No live companion, CI green, tag, deploy, publish, or `/sdd.finish` is claimed.
- A normal StoreCore start still seeds `BLACKSTORE_INTEGRATION` as `DISABLED`.

## Not granted

This APPROVED is this lane's scope verdict only. Merge stays with the other fresh Grok 4.7 PR review. Still NO-GO: companion live, activating `BLACKSTORE_INTEGRATION`, a browser call to StoreCore, fiscal/ARCA, MP-LIVE-05, tag, deploy, publish, and `/sdd.finish`.
