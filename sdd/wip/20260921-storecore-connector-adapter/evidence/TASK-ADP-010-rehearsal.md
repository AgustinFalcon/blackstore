# TASK-ADP-010 — Rehearsal de recovery

**Fecha:** 2026-09-23  
**Límite:** scripted + control plane. Sin rollout live.

El rehearsal cubre reserve: timeout, PENDING, EXPIRED, CONFLICT→GET `PENDING` y un same-body POST (el segundo reserve cierra `RESERVED`), 410, reconcile unknown, rotación y rollback. CONFLICT de commit/release (GET durable, POST acotado, no POST si el GET ya es el otro terminal) vive en `StoreCoreDurableSagaTest`.

`StoreCoreRecoveryRehearsalTest` OK.
