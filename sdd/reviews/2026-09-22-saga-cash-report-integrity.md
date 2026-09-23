# Review — saga, cash and report integrity

**Task:** TASK-014 · **Date:** 2026-09-22

No P0. `AutonomousCoreTest` covers:

- outbox reserve command persisted before the fixture call, including a crash that leaves `PENDING_RESERVATION` without receipt
- replay of a reserved sale does not issue another reserve
- `OPERATION_RETIRED` is `retryable=false` and a later reserve is rejected without a new POST
- one open cash session per terminal, audited closure, append-only ledger and idempotent inbox
- net sales, cash flow, unknown margin without validated cost, and non-fiscal projections
- reconciliation requires a non-blank reason; remote evidence is absent or a complete tuple

Reconcile stays read-only in the domain. No StoreCore write is performed.
