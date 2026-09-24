VERDICT: APPROVED

Lane: SCOPE. Independent review of https://github.com/AgustinFalcon/blackstore/pull/5 (`feature/blackstore-catalog-reserve-lines` vs `origin/master`, commit `c84c95ca8780c00f035e3e40234f11cab162236a`). The working tree stayed on `chore/blackstore-record-pr2-pr3`; the diff and tests were read from that commit. This file does not merge, tag, deploy, publish, or run `/sdd.finish`. GitHub reported no checks on this branch; that is not CI green.

## Why

PR title: "Send the catalog variant and price version on reserve." The body says a reserve for a SKU on the current catalog sends that item's variant and price version, not a fixed variant-1. If the catalog row has no price version, the cashier value stays. An unknown SKU blocks the sale. Default fixture mode stays unchanged, and the PR does not activate StoreCore or open a browser call.

`sdd/STATUS.md` on the current gate still says companion live is not approved, a normal StoreCore start leaves `BLACKSTORE_INTEGRATION` disabled, the browser does not call StoreCore, and fiscal, companion live, MP-LIVE-05, and `/sdd.finish` are NO-GO. This branch does not edit that status.

## Validations run

`git diff origin/master...c84c95c`: 1 commit (`c84c95c`), 3 files, +201/−2. Paths:

- `backend/src/main/kotlin/com/blackstore/application/sales/LocalSaleSagaService.kt`
- `backend/src/main/kotlin/com/blackstore/domain/catalog/CatalogReserveLinePolicy.kt`
- `backend/src/test/kotlin/com/blackstore/domain/catalog/CatalogReserveLinePolicyTest.kt`

`backend/src/main/resources/application.yml` is not in the diff. `git diff origin/master c84c95c -- backend/src/main/resources/application.yml` is empty. `application-loopback.yml` is not in the diff. No frontend, migration, profile, or secret file changed.

At `c84c95c`, `application.yml` still has `blackstore.storecore.integration.enabled: false`, `mode: fixture`, `transport.kill-switch: true`, `allow-plain-loopback: false`, and an empty `base-url`. There is no `spring.profiles.active`. Token and identity refs stay empty.

StoreCore sibling migrations, unchanged by this PR: `V5__blackstore_integration_registry.sql` inserts `BLACKSTORE_INTEGRATION` with state `DISABLED`. `V7__blackstore_future_optional_promotion.sql` requires that state to stay `DISABLED` and does not activate the module.

Focused tests from a detached worktree at `c84c95c`, `backend\gradlew.bat test --offline`:

- `CatalogReserveLinePolicyTest`: 4 tests, 0 failures
- `AutonomousCoreTest`: 10 tests, 0 failures
- `ArchitectureBoundaryTest`: 7 tests, 0 failures

`BUILD SUCCESSFUL`. `gh pr checks 5` reported no checks on `feature/blackstore-catalog-reserve-lines`.

## Diff scope

`CatalogReserveLinePolicy.resolve` reads the snapshot the saga already holds. A ticket SKU at the same index that is non-blank and different from the reserve line's `variantId` selects `snapshot.items` by SKU. A hit rewrites that line to the item's `variantId` and, when `priceVersion` is non-blank, to that version. A null or blank catalog `priceVersion` keeps `line.expectedPriceVersion`. Quantity is copied through. A miss throws `ForbiddenOperationException` (`sku … is not on catalog …`) before `reserveLinesByOperation` is stored, before the outbox, and before `inventoryPort.reserve`. `GlobalExceptionHandler` maps that exception to HTTP 403 `FORBIDDEN` with `retryable=false`, so the sale does not return success.

A blank ticket SKU, a ticket SKU equal to the line's `variantId`, or a null snapshot does not take the SKU-miss path. The null snapshot still fails `CatalogSalePolicy.assertSaleAllowed`. The equal-variant case is the existing request shape where `ReserveSaleRequest.ticketLines()` copies `variantId` into the ticket when `sku` is omitted; the line is then matched by `variantId`. The fixture row `SKU-1` / `variant-1` has no `priceVersion`, so a cashier line `variant-1` / `price-v1` stays that way. `FixtureStoreCoreInventoryAdapter` still answers locally and still records `acceptedPriceVersions` of `price-v1`.

`LocalSaleSagaService.beginReserve` stores the resolved lines on the operation id and passes those lines to `callReserve`. A same-body reserve retry reads that stored list. Commit and release still send only the reservation ref. The controller, the ticket UI, and `API_BASE` are unchanged. The ticket copy that says the reserve uses the local simulator and does not call StoreCore is outside this diff.

`CatalogReserveLinePolicy` is plain Kotlin in `domain.catalog`. It does not import Spring, HTTP, or infrastructure. `ArchitectureBoundaryTest.domainMustNotDependOnSpringOrJpa` passed.

## What holds

- A SKU on the current snapshot replaces a fixed `variant-1` and `price-v1` with that item's `variantId` and `priceVersion`.
- A missing catalog price version keeps the cashier value, including the fixture row that has none.
- An unknown ticket SKU blocks the sale before any reserve call.
- `application.yml` is outside the diff and stays fixture and fail-closed.
- No browser file, live profile, secret, tag, deploy, or `/sdd.finish` is in the change.
- A normal StoreCore start still seeds `BLACKSTORE_INTEGRATION` as `DISABLED`.

## Not granted

This APPROVED is this lane's scope verdict only. Merge stays with the other fresh Grok 4.7 PR review. Still NO-GO: companion live, activating `BLACKSTORE_INTEGRATION`, a browser call to StoreCore, fiscal/ARCA, MP-LIVE-05, tag, deploy, publish, and `/sdd.finish`.
