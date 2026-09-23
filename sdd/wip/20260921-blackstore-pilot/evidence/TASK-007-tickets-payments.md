# TASK-007 — Tickets and split payments

**Completed:** 2026-09-22

`TicketLine` snapshots SKU, product, original price, discount and effective price. `PaymentBook.reverse` appends a refund that references the captured payment. The original status stays `CAPTURED`.

Gate: `AutonomousCoreTest.splitPaymentReversalDoesNotMutateOriginal`.
