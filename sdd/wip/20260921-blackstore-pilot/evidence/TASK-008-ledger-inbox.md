# TASK-008 — Cash ledger, expenses, audit and inbox

**Completed:** 2026-09-22

`AppendOnlyLedger` allows one OPENING, rejects a zero non-opening amount, and rejects mutation. `InboxDeduplicator` treats the same quadruple, kind and response hash as a no-op. Database append-only for audit remains the TASK-003 smoke.

Gate: `AutonomousCoreTest.ledgerAndInboxAreAppendOnlyAndIdempotent`.
