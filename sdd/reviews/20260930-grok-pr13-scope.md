VERDICT: APPROVED

Lane: SCOPE/IMPLEMENTATION. Independent review of https://github.com/AgustinFalcon/blackstore/pull/13 (`feature/dispatcher-provider-injection` vs `origin/master` `1206b3220e2a82e7425de3dfd75422facdab672d`, HEAD `bd4e6e6d06e7d99d12c9a255e686a6c0567f6c6e`). Issue https://github.com/AgustinFalcon/blackstore/issues/12. This APPROVED is this lane only. It does not merge, tag, deploy, publish, approve on GitHub, or run `/sdd.finish`. Merge still needs the other PR #13 lane APPROVED.

## Why

PR title: "Inject DispatcherProvider for loopback HTTP IO." The body injects `DispatcherProvider` so `StoreCoreHttpTransport` receives its IO thread from Spring instead of a hardcoded `Dispatchers.IO` default. It applies that only at `StoreCoreHttpTransport` and the loopback configuration. Domain, the ticket saga, and `@Scheduled` stay on Spring threads. Tests swap `TestDispatcherProvider`. It says this is not an ML dispatcher, and there is no live companion. The test plan leaves dual Grok and the no-fiscal / no-live / no-`sdd.finish` lines open.

Issue #12 asks for the same edge, backend-correct: `DispatcherProvider` with `io` only (no Android `Main`), a Spring `@Bean` `ServerDispatcherProvider(Dispatchers.IO)`, and injection only into `StoreCoreHttpTransport` plus loopback configuration. Out of scope there: `LocalSaleSagaService`, ticket domain, StoreCore `@Scheduled` workers, fiscal, live host, `sdd.finish`, and POS-06/07/08.

`ArchitectureBoundaryTest.applicationMustNotDependOnInfrastructure` forbids `..application..` depending on `..infrastructure..`. The provider therefore lives under `com.blackstore.infrastructure.concurrency`.

## Validations run

`origin/master...HEAD` is one commit, 12 files, +163/−23. Production code is the three concurrency types, `StoreCoreHttpTransport`, and `StoreCoreLoopbackConfiguration`. Tests cover the transport, loopback/default Spring beans, `DispatcherProviderTest`, and `TestDispatcherProvider`. Docs are `sdd/PATTERNS.md`, `sdd/STATUS.md`, and `sdd/TRACEABILITY.md`.

No domain, application, frontend, route, `application.yml`, Flyway, fiscal, GRANT, or workflow file is in the diff.

`git diff --check origin/master...HEAD` exited 0.

`Thread.sleep` does not appear under `backend` Kotlin sources. `DispatcherProvider`, `Dispatchers`, and `runBlocking` in `backend/src/main` appear only under `infrastructure.concurrency` and `infrastructure.storecore.StoreCoreHttpTransport`. `backend/src/main/kotlin/com/blackstore/domain` and `.../application` have no dispatcher hop.

`backend/src/main/resources/application.yml` is outside the diff. On this tip it still has integration `enabled: false`, `mode: fixture`, and `allow-plain-loopback: false`. `frontend/src/app/app.routes.ts` is outside the diff and still only `''`, `caja`, `catalogo`, `ticket`, `reportes`, plus a wildcard back to `''`.

This lane did not start Gradle. A Gradle 9.7.1 daemon (pid 27324) and a Kotlin compile daemon (pid 46384) were already running. Luna recorded the focused Gradle tests as exit 0 (`DispatcherProviderTest`, `StoreCoreHttpTransportTest`, default/loopback Spring beans, `ArchitectureBoundaryTest`).

`gh pr checks 13` reported no checks on `feature/dispatcher-provider-injection`. Absent checks are not CI green.

## Diff scope

`DispatcherProvider` exposes only `io: CoroutineDispatcher`. `ServerDispatcherProvider` defaults that to `Dispatchers.IO`. `DispatcherConfiguration` is an always-on `@Configuration` whose `@Bean dispatcherProvider()` returns `ServerDispatcherProvider()`. `BlackStoreApplication` is `@SpringBootApplication` on `com.blackstore`, so that configuration is on the component scan. There is no `Main` and no `Default`.

`StoreCoreHttpTransport` takes `dispatchers: DispatcherProvider = ServerDispatcherProvider()` before `sleeper`. The default sleeper and `send` both use `runBlocking(dispatchers.io)`. `StoreCoreLoopbackConfiguration.storeCoreHttpTransport` is still `@ConditionalOnProperty(... mode=loopback)` and passes the Spring `DispatcherProvider` by name (`dispatchers = dispatchers`). The token resolver stays the local loopback ref. No other production constructor takes the provider.

`StoreCoreHttpTransportTest.http` builds `TestDispatcherProvider` and passes `dispatchers` as a named argument. A custom sleeper is also named (`sleeper = sleeper`). The token lambda at the kill-switch call stays inside the parentheses as the second parameter (`resolveToken`), so it does not bind to `sleeper`. `injectedDispatcherReceivesHttpHop` passes a recording dispatcher with `dispatchers = TestDispatcherProvider(recording)` and expects at least one hop on GET. `defaultCoroutineWaitHonorsZeroRetryAfter` still uses the default sleeper (`delay` on the injected IO dispatcher) against `127.0.0.1`. The token in that test is still `synthetic-not-reusable`.

`DispatcherProviderTest` checks `ServerDispatcherProvider().io` is `Dispatchers.IO`, the test provider defaults to `Dispatchers.Unconfined`, and `DispatcherConfiguration().dispatcherProvider()` is a `ServerDispatcherProvider` whose `io` is `Dispatchers.IO`. `DefaultStoreCoreBeansTest` and `LoopbackStoreCoreBeansTest` autowire `DispatcherProvider` and assert `ServerDispatcherProvider`.

`sdd/PATTERNS.md` records injection only at the blocking HTTP edge. `sdd/STATUS.md` records issue #12 the same way and keeps companion live, fiscal, MP-LIVE-05, and `/sdd.finish` as NO-GO. It says GitHub reported no checks and that is not CI green. `sdd/TRACEABILITY.md` maps issue #12 to constructor/Spring IO, not domain or `@Scheduled`. The WIP is not archived.

`AGENTS.md` is unchanged: a BlackStore USER is not a StoreCore CUSTOMER. This diff has no CUSTOMER identity, session, or route.

`ArchitectureBoundaryTest` is unchanged and still forbids application → infrastructure. The new type is infrastructure-only. No application or domain file in this diff references it, so that rule still holds for this change.

## What holds

- Constructor order is `client`, then `dispatchers`, then `sleeper`. A trailing lambda on `StoreCoreHttpTransport` still binds to `sleeper`. The test helper uses named `dispatchers` and named `sleeper`, so a token lambda is not passed as the sleeper.
- Spring wires one `DispatcherProvider` bean (`ServerDispatcherProvider`, `Dispatchers.IO`) and the loopback transport receives it. Fixture mode can still see the bean; loopback remains opt-in.
- Sync callers stay sync. `runBlocking` remains only on this infrastructure HTTP edge, now on `dispatchers.io`.
- Domain, application, `LocalSaleSagaService`, and `@Scheduled` do not hop dispatchers.
- Dispatch still goes through the existing fail-closed loopback guard. `application.yml` stays fail-closed and is not in the diff.
- BlackStore USER is not turned into a StoreCore CUSTOMER.
- No live companion, Mercado Libre dispatcher or `SENT`, fiscal emission, browser credential, POS-06/07/08 route, or GRANT.
- The PR and STATUS deny CI green and `/sdd.finish`. GitHub checks did not run.

## Not granted

This file does not authorize merge by itself. Still NO-GO: live companion, Mercado Libre `STOCK_DESIRED_CHANGED` or delivery `SENT`, fiscal/ARCA, browser credentials, POS-06/07/08 routes, GRANT, tag, deploy, publish, calling hosted CI green, and `/sdd.finish`.
