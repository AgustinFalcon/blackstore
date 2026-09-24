VERDICT: APPROVED

Lane: SCOPE. Independent GitHub PR review of https://github.com/AgustinFalcon/blackstore/pull/2 (`feature/blackstore-loopback-profile` vs `origin/master`, HEAD `e456497`). Base `origin/master` is `164ca8a`. Sol why is `sdd/reviews/20260923-sol-next-dev-go.md` and `sdd/reviews/20260923-sol-post-l3-go.md`: localhost/fixture work may exist only while defaults stay fail-closed; a live companion, a real secret, a browser call to StoreCore, tag, deploy, publish, and `/sdd.finish` stay NO-GO. This file does not merge.

## Why

The PR title is "Opt-in local loopback to StoreCore". The body matches the diff: an opt-in Spring profile `loopback` may call StoreCore on `http://127.0.0.1:8080`; the default stays fixture with integration off and the kill switch on; the token ref `local-loopback-token` is a synthetic name, not a vendor credential. The body does not claim a live companion, CI green, or merge approval.

`git diff origin/master...HEAD` for this branch is one commit, six files, all under `backend/`. `backend/src/main/resources/application.yml` is not in the diff. No frontend file, no `sdd/` edit, no StoreCore `EffectivePrice*.kt`, and no `docs/agent/*`.

## Validations run

From the backend of `feature/blackstore-loopback-profile` at `e456497`, after reading the PR body, the Sol gates, and the full branch diff:

```text
.\gradlew.bat test --tests com.blackstore.connector.StoreCoreLoopbackProfileTest --tests com.blackstore.connector.DefaultStoreCoreBeansTest --tests com.blackstore.connector.LoopbackStoreCoreBeansTest --tests com.blackstore.architecture.ArchitectureBoundaryTest --offline
```

BUILD SUCCESSFUL in 1m 4s. `:test` executed (`6 actionable tasks: 6 executed`), not UP-TO-DATE. XML reports: 12 tests, 0 failures, 0 errors, 0 skipped. Local Gradle only. `--offline` succeeded against the existing cache.

| Suite | tests | failures |
|---|---|---|
| `StoreCoreLoopbackProfileTest` | 3 | 0 |
| `DefaultStoreCoreBeansTest` | 1 | 0 |
| `LoopbackStoreCoreBeansTest` | 1 | 0 |
| `ArchitectureBoundaryTest` | 7 | 0 |

`gh pr checks 2` reported no checks on `feature/blackstore-loopback-profile`. GitHub CI did not run and is not green.

`git diff --check origin/master...HEAD` produced no output and exited 0.

## Diff scope

Commit: `e456497` Add an opt-in loopback profile for the StoreCore transport.

| File | Role |
|---|---|
| `application-loopback.yml` | Only committed opt-in. Profile `loopback` sets mode, URL, and switches. |
| `StoreCoreLoopbackConfiguration.kt` | `@ConditionalOnProperty` `mode=loopback`. No `@Profile` on the default app. |
| `StoreCoreIntegrationProperties.kt` | Binds `blackstore.storecore`. Defaults in code are fail-closed. |
| `LoopbackCatalogAdapter.kt` | Catalog GET through the existing guarded transport. |
| `StoreCoreCatalogPort.kt` | Optional `priceVersion` and `unitPrice` on `CatalogItem`. |
| `StoreCoreLoopbackProfileTest.kt` | Default fixture bean, loopback HTTP beans, plaintext non-loopback rejection. |

`backend/src/main/resources` and `backend/src/test/resources` contain no `spring.profiles` entry. Nothing in the repo activates `loopback` by itself.

## What holds

- Default `application.yml` on this branch is the master file: `integration.enabled: false`, `mode: fixture`, transport kill-switch true, TLS required, plain loopback false, empty base URL and identity/token refs, capability false, control kill-switch true, canary 0, rollout false, digest `7b907a2e11c52a66b7253407fb3f9450cae7b792beccf34c1636be9d3945de30`. `FixtureCatalogAdapter` and `FixtureStoreCoreInventoryAdapter` stay `mode=fixture` with `matchIfMissing=true`. `DefaultStoreCoreBeansTest` injects `FixtureCatalogAdapter`.
- The only file that turns integration on is `application-loopback.yml`: `enabled: true`, `mode: loopback`, both kill switches false, `tls-required: false`, `allow-plain-loopback: true`, `base-url: http://127.0.0.1:8080`, `identity-ref: blackstore-service-local`, `token-ref: local-loopback-token`, `capability-active: true`, canary 0, rollout false. It does not set a contract digest; the default pin still applies. `LoopbackStoreCoreBeansTest` with `@ActiveProfiles("loopback")` injects `LoopbackCatalogAdapter` and `TransportStoreCoreInventoryAdapter`.
- `StoreCoreDispatchGuard` still rejects a disabled integration, a kill switch, an inactive capability, a blank identity or token ref, a blank URL, TLS-required plaintext, and plaintext whose host is not `127.0.0.1`, `localhost`, or `::1`. The new test dispatches the loopback settings and rejects `http://example.com`. Unresolved token refs throw `STORECORE_TOKEN_UNRESOLVED` before an `Authorization` header is built.
- The bearer value in source is the literal `local-loopback`, returned only for the ref `local-loopback-token`. No password, PEM, `AKIA`, `sk_live`, or vendor credential is in the diff. `client-instance-id` is the synthetic UUID `11111111-1111-1111-1111-111111111111`.
- This diff does not change the frontend. On this branch `API_BASE` remains `http://localhost:8081/api/v1`. There is no browser call to port 8080 or to a StoreCore host.
- `ArchitectureBoundaryTest` still passes: domain stays free of Spring/JPA, application stays free of infrastructure, and resources do not reference a StoreCore JDBC URL. `CatalogItem` only adds optional fields; the fixture constructor is unchanged.
- Setting `mode=loopback` without the rest of the profile still fails closed. Bean creation calls `assertCanDispatch`, and the default YAML leaves integration disabled, the kill switch on, and the URL empty.

## Notes that do not change this verdict

- `TransportStoreCoreInventoryAdapter` still has no stereotype. Its KDoc still says it is not a Spring bean. The new `@Bean` exists only inside `StoreCoreLoopbackConfiguration` when `mode=loopback`. The default inventory port stays the fixture.
- The pre-existing guard allows HTTPS to a non-loopback host if settings are overridden later. This profile's only URL is `http://127.0.0.1:8080`. The new test covers plaintext outside loopback, which is what the PR test plan claims.
- This review did not recompute the OpenAPI file hash and did not start a StoreCore process.

## Not granted

This APPROVED is this lane's PR-scope verdict only. Merge stays NO-GO until the other fresh Grok 4.7 review of PR 2 is also APPROVED. Still NO-GO: live companion, any host other than loopback, real secrets, browser-to-StoreCore HTTP, operational canary or rollout, fiscal/ARCA, MP-LIVE-05, tag, deploy, publish, and `/sdd.finish`. GitHub CI is not green.
