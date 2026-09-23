# TASK-010 — Fiscal boundary

**Completed:** 2026-09-22

`FiscalBoundaryPolicy` allows `NOT_CONFIGURED` in test. Production rejects `NOT_CONFIGURED` and rejects COMMITTED without a valid external mechanism or lawful exception signed by the responsible person and the accountant. No emission adapter is implemented.

Gate: `AutonomousCoreTest.productionCommitRequiresFiscalAuthorizationAndDoesNotEmit`.
