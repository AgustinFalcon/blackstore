# TASK-ADP-009 — Secuencia loopback

**Fecha:** 2026-09-23  
**Límite:** HttpServer 127.0.0.1. Sin DB StoreCore ni companion live.

Secuencia catalog GET + reserve/GET/commit/release/reconcile vía `TransportStoreCoreInventoryAdapter` (no bean) contra HttpServer 127.0.0.1. Mutating calls: Bearer + cuatro headers. Reconcile: Bearer + `X-Client-Instance-Id` solamente; `present` usa el cuádruple del envelope, no uno inventado. Rates del pin. Capability disabled = 0 hits.

`StoreCoreContractSequenceTest` OK.
