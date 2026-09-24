# TASK-ADP-L3-001 — Architecture and contract isolation

**Fecha:** 2026-09-23  
**Límite:** review local. Sin host live. Sin merge.

Review: `sdd/reviews/20260923-grok-adp-l3-arch-resilience.md` — VERDICT APPROVED, no P0.

Dominio StoreCore sin HTTP/Spring. Transporte HTTP no es bean. Adapter default `fixture`. Un solo pin canónico `7b907a2e…de30`. Sin copia OpenAPI. JDBC solo `localhost:5433/blackstore` (outbox/inbox propios). Sin ruta/credencial a una base StoreCore.

Este archivo no autoriza companion live ni `/sdd.finish`.
