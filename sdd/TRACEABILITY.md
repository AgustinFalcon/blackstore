# RTM — `blackstore-pilot`

**Estado:** `ready_for_sol_review`; no approved. El RTM `blackstore-pos-core-v1.0.0` es histórico.

| Capacidad | Tarea | Gate |
|---|---|---|
| skeleton hexagonal + POS Angular | TASK-001 | domain sin Spring |
| Flyway modelo BlackStore | TASK-002 | sin `store_id`, sin tablas StoreCore |
| operadores USER y roles | TASK-003 | CASHIER/SUPERVISOR/OWNER/AUDITOR; no CUSTOMER StoreCore |
| ticket + saga reserve/commit | TASK-004 | AC-4 cuádruple + outbox |
| Contrato StoreCore v1 | externo `storecore-pos-integration-contract-v1` | OpenAPI canónico `/blackstore-integration/v1`; WIP no approved |
| Adaptador consumidor StoreCore | `20260921-storecore-connector-adapter` | `ready_for_sol_review`; consume sólo 1.0.0-draft SHA-256 bloqueado después de evidencia TASK-PIC-001..008 + GO Sol propio |
| pos-core ISSUE | `20260921-blackstore-pos-core` | **superseded** — evidencia histórica |
| caja y arqueo | TASK-007 | AC-5 |
| tests de contrato e invariantes | TASK-010 | idempotencia, inbox PENDING, reconcile read-only |

## Adapter dependency map
