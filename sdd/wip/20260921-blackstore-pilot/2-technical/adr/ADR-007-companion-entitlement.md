# ADR-007 — Companion entitlement

**Status:** proposed

One StoreCore installation can initially map to zero or one BlackStore companion. BlackStore startup and write work require ENABLED entitlement and a client identity bound to that installation. Mismatch, missing entitlement or a second companion mapping fails closed. Multiple branches are deferred.
