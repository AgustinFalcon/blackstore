# BlackStore documentation baseline 1.0.0

**Estado:** DRAFT / `ready_for_sol_review`. `sdd-v1.0.0` es baseline documental; `blackstore-pilot` nombra el companion vivo, no un tag.

| Artefacto | Qué es | Estado |
|---|---|---|
| `sdd-v1.0.0` | baseline SDD | draft, sin tag |
| `blackstore-pos-core-v1.0.0` | POS ISSUE histórico | **superseded** |
| `blackstore-pilot` | companion + puertos | ready_for_sol_review, conector bloqueado |
| `storecore-pos-integration-contract-v1` (externo) | OpenAPI canónico StoreCore | **WIP** no approved |

## Antes de GitHub / release

Corte DCT local: backend 143 tests PASS y UI typecheck PASS; Integration global BLOCKED por browser/reinicio y build/tests UI del entorno, PG16/suite Docker pendientes. Revisión final y CI hospedado sin ejecutar en este diff. PostgreSQL de prueba detenido y recursos propios verificados ausentes; no commit, push, PR, merge, tag ni publicación. Ver evidencia T05/T06 del WIP; no altera la baseline ni habilita `/sdd.finish`.

El WIP [durable-cash-transaction-boundary](wip/20261006-durable-cash-transaction-boundary/meta.md) es una propuesta de corrección core local; no cambia la baseline, no crea tag ni habilita publicación. Su aceptación requiere transacciones, crash, concurrencia y reinicio PostgreSQL más browser real y reviews exactas. No acredita libro de caja, arqueo, reportes ni homologación StoreCore/fiscal. Decisión inicial: sin cambio de schema ni migración; rollback preserva historia y detiene escrituras si se vuelve a la versión con la brecha.

- [ ] Sol aprueba specs, modelo y tasks.
- [ ] StoreCore `storecore-pos-integration-contract-v1` con Sol GO (hoy **WIP no approved**).
- [ ] Secretos por referencia. No credenciales en el repo.
- [x] Remote GitHub creado por el titular: `https://github.com/AgustinFalcon/blackstore` (privado).
- [ ] Facturación homologada con su contrato, evidencia y responsables aprobados.
- [ ] Correo Argentino homologado con adapter oficial, credenciales por referencia y evidencia E2E.
- [ ] Crear `release/1.0` desde el `master` homologado; integrar StoreCore↔BlackStore sólo en ese carril.
