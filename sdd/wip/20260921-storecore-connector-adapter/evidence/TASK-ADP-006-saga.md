# TASK-ADP-006 — Saga durable local

**Fecha:** 2026-09-23  
**Límite:** fixture/scripted/localhost. Sin host live.

`LocalSaleSagaService` persiste intent/outbox **antes** del puerto en reserve/commit/release. `StoreCoreRecoveryPolicy` mapea PENDING, NOT_FOUND (el mismo `operation_id` queda bloqueado; este tramo no crea un id de reemplazo), CONFLICT→GET y se guarda el receipt antes de cualquier POST, mismatch sin mutar, EXPIRED→`RECONCILIATION_REQUIRED` con tupla y reason (también desde COMMIT_PENDING/RELEASE_PENDING), 410 never-repost. `PENDING` queda pending sin evidencia inventada. `RESERVED`/`COMMITTED`/`RELEASED` reemplazan la tupla durable. Same-body POST acotado (`sameBodyRetries < 1`) solo si el paso guardado sigue incompleto, usando el ref guardado: reserve si el GET es `PENDING`; commit no POSTea si el GET ya es `RELEASED`; release no POSTea si el GET ya es `COMMITTED`. El gate no lee `retryable`. Un GET null no POSTea. `recoverWithGet` no deja el siguiente reserve en GET-only. `StoreCoreHttpTransport` no es bean; el default sigue `fixture`. Digest canónico `7b907a2e…de30`.

`gradlew test --tests com.blackstore.connector.StoreCoreDurableSagaTest --tests com.blackstore.domain.AutonomousCoreTest` OK.
