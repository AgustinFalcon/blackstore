# TASK-ADP-L3-002 — Performance resilience and recovery

**Fecha:** 2026-09-23  
**Límite:** review local. Sin host live. Sin merge.

Review: `sdd/reviews/20260923-grok-adp-l3-arch-resilience.md` — VERDICT APPROVED, no P0.

Retry conserva el mismo `operation_id`. 429 / POST incierto no mintan id. CONFLICT: GET, se guarda el receipt, un same-body POST solo si el paso sigue incompleto. 410 y error desconocido never-repost. Reconcile unknown no autoriza POST. EXPIRED → `RECONCILIATION_REQUIRED`.

Este archivo no autoriza canary operacional ni `/sdd.finish`.
