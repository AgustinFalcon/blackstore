# ADR-004 — Immutable events, controlled projections

**Status:** proposed

Sale, cash ledger, expense, inbox/outbox command and audit are immutable historical events: app role has INSERT/SELECT only and triggers reject UPDATE/DELETE. `blackstore_app` atomically INSERTs only initial sale/cash projections plus immutable events/commands under narrow grants; initial triggers reject advanced state. `sale_state_projection` and `cash_session_projection` are state projections; projection worker INSERTs recovery projections and alone UPDATEs them through allowed transition triggers. `outbox_delivery_attempts` is the mutable delivery-work table and may change attempt state, never the original command. Migration owner is not a runtime role.

Opening cash admits zero; zero amount is otherwise invalid. Reversal/adjustment requires original reference, actor, reason and evidence and appends a compensating event. Split payments, fees and accrued/paid expenses are distinct events.
