VERDICT: APPROVED

Lane: L3 ARCHITECTURE + RESILIENCE (`TASK-ADP-L3-001`, `TASK-ADP-L3-002`). Independent local review of BlackStore `feature/blackstore-frontend-ux-system-v1` at HEAD `7e8338a` plus uncommitted ADP-005..010. Not a GitHub merge review and not merge approval. No commit, push, tag, deploy, publish, or `/sdd.finish`.

This APPROVED is local evidence only. It does not grant live companion, fiscal, MP-LIVE-05, secret, tag, deploy, publish, POS-06/07/08, or archive approval. Those remain NO-GO under Sol `sdd/reviews/20260923-sol-next-dev-go.md`.

## Why

Sol is `CONDITIONAL_GO` for fixtures, mocks, localhost, and controlled ephemeral StoreCore/Testcontainers. Scope r4 and SDD r4 approved the ADP-005..010 implementation text and left L3 pending. `tasks.json` still marks `TASK-ADP-L3-001` and `TASK-ADP-L3-002` `pending`. This file records the two gates. It does not edit `tasks.json` and it does not close L3-003.

No P0. Isolation holds, HTTP/Spring stay out of the domain, the only JDBC URL is the BlackStore database, recovery does not mint a new operation id, and 410 / unknown reconcile do not re-POST a sale operation. Default composition stays fail-closed and does not claim live readiness.

## L3-001 — architecture and contract isolation

AC-1 holds: no HTTP/framework leak into the domain, and no second contract.

- `com.blackstore.domain` has no import of `org.springframework`, `java.net.http`, `okhttp3`, or `org.springframework.jdbc`. Domain StoreCore types are contract-neutral: `StoreCoreCanonicalContract`, `StoreCoreRecoveryAction`, `StoreCoreRemoteEvidence`, `StoreCoreOperationReceipt`, `StoreCoreOperationState`, `StoreCoreOperationKind`, `StoreCoreContractRef`, `StoreCoreRemoteFault`, and the outbound ports. `StoreCoreRecoveryAction` carries no HTTP status. `StoreCoreRemoteEvidence.authorizesRepost()` is constantly false. `PENDING` evidence cannot invent a receipt or reservation ref.
- `ArchitectureBoundaryTest` bars Spring/JPA/Hibernate from `..domain..`, bars application from infrastructure, and bars `java.net.http`, OkHttp, Spring Web, and Spring JDBC from the storecore domain ports, application ports, `application.storecore`, `application.dto.storecore`, and `domain.model`. Port types whose names end in `Port` must live under `domain.port.out`.
- `java.net.http.HttpClient` is used only in `infrastructure.storecore.StoreCoreHttpTransport`. `TransportStoreCoreInventoryAdapter` maps that client onto `StoreCoreInventoryPort` and `StoreCoreReconcilePort`. Neither class has a Spring stereotype or a `@Bean`. The inventory bean that matches `mode: fixture` (and matches when the property is missing) is `FixtureStoreCoreInventoryAdapter`.
- `StoreCoreDispatchGuard` parses `java.net.URI` to enforce TLS and loopback. That is not an HTTP client and it is not in the domain.
- One pin: path `/blackstore-integration/v1`, version `1.0.0-draft`, SHA-256 `7b907a2e11c52a66b7253407fb3f9450cae7b792beccf34c1636be9d3945de30`. The same triple is in `StoreCoreCanonicalContract` and `application.yml`. `StoreCoreContractCompatibilityGuard` calls `assertCompatible` at startup. `StoreCoreContractOperations` / `StoreCoreContractFacade` delegate to the domain ports (catalog, reserve, commit, release, get, reconcile). They are not a second OpenAPI. `StoreCoreConsumerContractTest` asserts the OpenAPI file is not copied into BlackStore test resources. This review compared those three strings to the digest Sol already recorded. It did not recompute the YAML hash.

AC-2 holds: no cross-database route, credential, or query.

- The only `jdbc:postgresql` URL under BlackStore is `jdbc:postgresql://localhost:5433/blackstore` in `application-local.yml`. `ArchitectureBoundaryTest` fails a resource that matches `jdbc:postgresql://.*storecore` and fails `build.gradle.kts` if it names StoreCore or a StoreCore JDBC driver. Both checks passed in this run.
- `JdbcSaleRecordStore` uses that BlackStore `DataSource` and roles `blackstore_app` / `blackstore_projection_worker`. `storecore_outbox_commands`, `storecore_inbox_events`, and `sale_state_projection` are local BlackStore tables. They are not a connection, credential, or SQL session against a StoreCore database.
- `application.yml` leaves `base-url`, `identity-ref`, and `token-ref` empty, `integration.enabled: false`, `mode: fixture`, both kill switches true, `allow-plain-loopback: false`, `capability-active: false`, `canary-percent: 0`, and `rollout-enabled: false`.

GATE: no P0.

## L3-002 — performance resilience and recovery

AC-1 holds: retry stays on the contract and does not create another operation.

- Durable intent is stored before the port call (`INTENT_OUTBOX` precedes the scripted reserve). A crash leaves `PENDING_RESERVATION`.
- HTTP retry is status 429 only, same `OperationQuadruple` headers, including `X-Operation-Id`. `StoreCoreHttpTransportTest` records two POSTs and the same `op-1` after one `Retry-After`. `waitMillis` reads `Retry-After` and caps the wait at 5 seconds. `shouldRetryPost(false, …)` does not retry an incomplete attempt. The loop stops once `attempt` reaches `maxRetries`.
- An uncertain POST (the test drops the first POST) performs one POST and then GET. `recoveredViaGet` is true and the POST count stays 1.
- `CONFLICT` is `GET_SAME_QUADRUPLE`, then at most one same-body POST (`sameBodyRetries < 1`) while that step is still incomplete, using the stored reservation ref. Reserve GET `PENDING` posts the same reserve once and a later `beginReserve` does not post again. Commit GET `RESERVED` posts commit once with the GET ref. Commit GET `RELEASED` and release GET `COMMITTED` do not POST. A direct `PENDING` receipt stays pending with null evidence; a second commit or release does not POST.
- `NOT_FOUND`, `INSUFFICIENT_STOCK`, `CATALOG_VERSION_STALE`, and `VALIDATION` are `NEW_OPERATION_SAME_SALE`. The saga sets `blockSameOperationRepost` and does not allocate an operation id. `notFoundKeepsPendingAndDoesNotReconcile` keeps a second reserve from posting. `IDEMPOTENCY_PAYLOAD_MISMATCH` retains the pending row and does not POST again.
- `SaleController` takes `operationId` from the caller. `UUID.randomUUID()` there is a trace id when `X-Trace-Id` is absent, not a StoreCore operation id.

AC-2 holds: 410 and unknown reconcile never re-POST.

- `OPERATION_RETIRED` and any unrecognized code, including `UNKNOWN_CODE`, map to `NEVER_REPOST`. The saga marks the operation retired and blocked. A second reserve, commit, or release throws `ForbiddenOperationException` before another port POST. Durable tests keep reserve, commit, and release attempt counts at 1 for `op-410`, `op-commit-410`, and `op-rel-410`. The rehearsal does the same for reserve.
- `ReconcileProjection.unknownAuthorizesRepost()` is constantly false. The contract reconcile route is itself `POST /blackstore-integration/v1/operations/reconcile` and is specified as zero writes. Unknown receipts are returned and do not authorize reserve, commit, or release. Receipt state `EXPIRED` projects `RECONCILIATION_REQUIRED` with the remote tuple and does not POST again.

GATE: no P0.

## Validations run

From `C:\Users\agustin\Desktop\BlackStore\backend`, after reading Sol, both r4 reviews, `tasks.json` L3-001/L3-002, and the saga, transport, guard, retry policy, JDBC store, `application.yml`, domain storecore types, and the three named test classes.

This invocation completed on the first try:

```text
.\gradlew.bat test --tests "com.blackstore.connector.*" --tests "com.blackstore.architecture.ArchitectureBoundaryTest" --tests "com.blackstore.domain.AutonomousCoreTest" --no-daemon
```

BUILD SUCCESSFUL in 33s. XML timestamp `2026-09-24T00:48:15Z`. 53 tests, 0 failures, 0 errors, 0 skipped. Local Gradle only. GitHub CI was not run and is not claimed green.

| Suite | tests | failures |
|---|---|---|
| `StoreCoreHttpTransportTest` | 8 | 0 |
| `StoreCoreDurableSagaTest` | 9 | 0 |
| `StoreCoreConsumerContractTest` | 2 | 0 |
| `StoreCoreControlPlaneTest` | 3 | 0 |
| `StoreCoreContractSequenceTest` | 2 | 0 |
| `StoreCoreRecoveryRehearsalTest` | 1 | 0 |
| `StoreCoreRecoveryPolicyTest` | 6 | 0 |
| `StoreCoreEnvelopeValidatorTest` | 5 | 0 |
| `ArchitectureBoundaryTest` | 7 | 0 |
| `AutonomousCoreTest` | 10 | 0 |

The saga and rehearsal tests use `InMemorySaleRecordStore`. This command does not execute `JdbcSaleRecordStore` SQL. The JDBC writer was reviewed by reading it: BlackStore datasource, local tables, caller `remoteState`, and `acceptedPriceVersions` passed through.

## Non-P0 notes

These do not change the verdict:

- `Retry-After` larger than 5 seconds is shortened to 5 seconds before the same quadruple is retried.
- Transport retries HTTP 429 only. A final `INTERNAL` / unrecognized error code takes `NEVER_REPOST` (no new id, no re-POST). Receipt state `EXPIRED` still reconciles.
- A non-UUID reservation ref may be stored locally with `UUID.nameUUIDFromBytes`. That value is an inbox column surrogate, not a new operation id, and it is not posted.

## Not granted

Fiscal/ARCA, MP-LIVE-05, live companion, real secrets, operational canary, POS-06/07/08 routes (`/sesion`, `/caja/cierre`, `/ticket/:saleId`), tag, deploy, publish, and `/sdd.finish` stay NO-GO. L3-003 is a separate review. This file does not approve a merge.
