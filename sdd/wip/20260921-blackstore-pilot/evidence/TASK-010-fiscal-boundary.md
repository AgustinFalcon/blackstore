# TASK-010 — Fiscal boundary

**Completed:** 2026-09-22

`FiscalBoundaryPolicy` records the sale in test and in production. Fiscal status does not block the commit. No emission adapter is implemented. V4 replaces the database functions that used to reject a production commit.

Gate: `AutonomousCoreTest.productionCommitRecordsAnySaleAndDoesNotEmit`.
