# TASK-006 — Local saga fixture

**Completed:** 2026-09-22

`LocalSaleSagaService` persists the RESERVE outbox before the fixture call. A crash leaves `PENDING_RESERVATION` without receipt. Replay of a reserved sale does not call reserve again. `StoreCoreEnvelopeValidator` requires the six BaseResponse fields, success nulls, and `OPERATION_RETIRED` with `retryable=false`. A retired operation is not posted.

Gate: `AutonomousCoreTest.sagaPersistsOutboxBeforeReserveAndDoesNotRepostRetiredOperation` and `envelopeRejectsAmbiguousSuccessAndRetryableTombstone`.
