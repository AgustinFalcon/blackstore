# BlackStore documentation baseline 1.0.0

**Estado:** DRAFT / `ready_for_sol_review`. `sdd-v1.0.0` es baseline documental; `blackstore-pilot` nombra el companion vivo, no un tag.

| Artefacto | Qué es | Estado |
|---|---|---|
| `sdd-v1.0.0` | baseline SDD | draft, sin tag |
| `blackstore-pos-core-v1.0.0` | POS ISSUE histórico | **superseded** |
| `blackstore-pilot` | companion + puertos | ready_for_sol_review, conector bloqueado |
| `storecore-pos-integration-contract-v1` (externo) | OpenAPI canónico StoreCore | **WIP** no approved |

## Antes de GitHub / release

Corte DCT integrado: [PR #26](https://github.com/AgustinFalcon/blackstore/pull/26) MERGED, head caab0a9 y merge master a9887a3; dual exact-head GPT-6.1 Sol APPROVED. [CI PR 37634224683](https://github.com/AgustinFalcon/blackstore/actions/runs/37634224683) y [CI postmerge master 37634797108](https://github.com/AgustinFalcon/blackstore/actions/runs/37634797108) verdes (postmerge frontend 34 s/backend 3 min 43 s). Regresión local final 153 tests/39 suites/0 failures/errors/skips, seis clases Docker-only excluidas, PG18 local y teardown PASS. PostgreSQL 16 y browser real con reinicio DCT pendientes; Integration PARTIAL. El merge y CI no homologan el producto. Sin archivo del WIP, /sdd.finish, tag, deploy ni publicación.

El WIP [durable-cash-transaction-boundary](wip/20261006-durable-cash-transaction-boundary/meta.md) es una corrección core local mergeada con aceptación residual; no cambia la baseline, no crea tag ni habilita publicación. Transacciones, crash y concurrencia PG18 cuentan con evidencia local; reviews exactas y CI están aprobados. La aceptación integral todavía requiere PG16 y browser real con reinicio DCT. No acredita libro de caja, arqueo, reportes ni homologación StoreCore/fiscal. Decisión inicial: sin cambio de schema ni migración; rollback preserva historia y detiene escrituras si se vuelve a la versión con la brecha.

- [ ] Sol aprueba specs, modelo y tasks.
- [ ] StoreCore `storecore-pos-integration-contract-v1` con Sol GO (hoy **WIP no approved**).
- [ ] Secretos por referencia. No credenciales en el repo.
- [x] Remote GitHub creado por el titular: `https://github.com/AgustinFalcon/blackstore` (privado).
- [ ] Facturación homologada con su contrato, evidencia y responsables aprobados.
- [ ] Correo Argentino homologado con adapter oficial, credenciales por referencia y evidencia E2E.
- [ ] Crear `release/1.0` desde el `master` homologado; integrar StoreCore↔BlackStore sólo en ese carril.
