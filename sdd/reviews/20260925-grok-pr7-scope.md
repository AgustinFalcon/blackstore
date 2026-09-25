VERDICT: APPROVED

Lane: SCOPE. Independent review of https://github.com/AgustinFalcon/blackstore/pull/7 (`feature/blackstore-loopback-reserve-body` vs `origin/master`, commit `c7fbd19cab0b527532e8f998e31b1765daa46717`). This file does not merge, tag, deploy, publish, or run `/sdd.finish`. GitHub reported no checks on this branch; that is not CI green.

## Why

PR title: "Post the catalog variant on a loopback reserve." The body says a loopback reserve POST carries the catalog variant and price version, that an `OPERATION_RETIRED` response stays one POST with no follow-up GET, and that default `application.yml` is unchanged. It says this stub is not a live StoreCore process.

The diff does not edit `sdd/STATUS.md`. Master still records companion live as unapproved and says a normal StoreCore start leaves `BLACKSTORE_INTEGRATION` `DISABLED`, so the two processes are not connected. This PR does not claim a live companion or that the two processes are connected.

## Validations run

`git diff origin/master...c7fbd19`: 1 commit (`c7fbd19`), 1 file, +107/−0. Path:

- `backend/src/test/kotlin/com/blackstore/connector/LoopbackReserveHttpTest.kt`

`backend/src/main/resources/application.yml` is not in the diff. `git diff origin/master...c7fbd19 -- backend/src/main/resources/application.yml` is empty. `application-loopback.yml` is not in the diff. No main source, frontend, migration, profile, or secret file changed.

At `c7fbd19`, `application.yml` still has `blackstore.storecore.integration.enabled: false`, `mode: fixture`, `transport.kill-switch: true`, `tls-required: true`, `allow-plain-loopback: false`, and an empty `base-url`. Identity and token refs stay empty. `capability-active` is false and `rollout-enabled` is false. There is no `spring.profiles.active`.

Focused test from `backend`, `.\gradlew.bat test --tests com.blackstore.connector.LoopbackReserveHttpTest --offline`:

- `BUILD SUCCESSFUL`. `:test` was `UP-TO-DATE`.
- `TEST-com.blackstore.connector.LoopbackReserveHttpTest.xml`: 1 test, 0 failures, 0 errors, 0 skipped (`reservePostCarriesTheCatalogVariantAndARetiredResponseDoesNotGet`).

`gh pr checks 7` reported no checks on `feature/blackstore-loopback-reserve-body`. `statusCheckRollup` is empty.

## Diff scope

`LoopbackReserveHttpTest` binds `127.0.0.1` on an ephemeral port and serves `StoreCoreCanonicalContract.CANONICAL_PATH` (`/blackstore-integration/v1`). That prefix also covers the reserve POST `/blackstore-integration/v1/reservations` and a recovery GET `/blackstore-integration/v1/operations/{operationId}`. The handler records every GET and every POST body on that prefix.

The reserve command is built in the test as `ReserveLineCommand("9", 1, "pv-9")`. `TransportStoreCoreInventoryAdapter.reserve` serializes `StoreCoreReserveLineDto(variantId, quantity, expectedPriceVersion)`. After the first call, the single POST body contains `"variantId":"9"` and `"expectedPriceVersion":"pv-9"`, the receipt state is `RESERVED`, and `acceptedPriceVersions` is `pv-9`. The test does not call the catalog adapter.

The second reserve receives HTTP 410 with `errorCode` `OPERATION_RETIRED` and `retryable` false. `StoreCoreHttpTransport.post` returns that completed response. It issues a GET only when `execute` throws. `parse` then throws `StoreCoreRemoteFault` outside `post`. The test asserts that fault, `retryable == false`, two POSTs, and zero GETs.

The test constructs transport settings with `enabled=true` and `allowPlainLoopback=true` only in memory. The token is the existing local constant `local-loopback`. The test does not start StoreCore and does not load `application.yml`.

## What holds

- A local stub on `127.0.0.1` receives a reserve POST whose body carries variant `9` and price version `pv-9`.
- An `OPERATION_RETIRED` response on that stub stays a single POST. No GET is recorded.
- `application.yml` is outside the diff and stays fixture and fail-closed.
- The PR text calls the stub not a live StoreCore process. It does not say the two processes are connected.
- No live companion, CI green, tag, deploy, publish, or `/sdd.finish` is claimed.
- The token in the test is the existing synthetic constant. No new credential is in the diff.

## Not granted

This APPROVED is this lane's scope verdict only. Merge stays with the other fresh Grok 4.7 PR review. This test does not read a catalog snapshot and does not exercise the saga `NEVER_REPOST` path. Still NO-GO: companion live, activating `BLACKSTORE_INTEGRATION`, a browser call to StoreCore, fiscal/ARCA, MP-LIVE-05, tag, deploy, publish, and `/sdd.finish`.
