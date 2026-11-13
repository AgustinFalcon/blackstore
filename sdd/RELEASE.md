# BlackStore documentation baseline 1.0.0

## Master vigente — 2026-11-12

`master 3c68c02` agrega por PR #67 evidencia temporal durable de ventas y pagos
sobre el cache durable de catálogo incorporado por PR #66. Verify post-merge
#125 (`43333400250`) quedó verde en sus cuatro jobs. El baseline sigue sin tag,
deploy, publicación ni homologación y `release/1.0` continúa diferido hasta los
gates externos.

## Master vigente — 2026-11-09

`master 40c2880` contiene PR #61 y la [paridad de datos de superficies productivas](features/20261109-blackstore-productive-surface-data-parity/meta.md), validada por Verify exact-head #109 y DCT real PostgreSQL 16/JAR/Angular 18/18. No existe tag, deploy, publicación ni homologación; `release/1.0` no se crea ni recibe StoreCore live hasta completar los gates externos acordados.

**Estado:** DRAFT / `ready_for_sol_review`. `sdd-v1.0.0` es baseline documental; `blackstore-pilot` nombra el companion vivo, no un tag.

| Artefacto | Qué es | Estado |
|---|---|---|
| `sdd-v1.0.0` | baseline SDD | draft, sin tag |
| `blackstore-pos-core-v1.0.0` | POS ISSUE histórico | **superseded** |
| `blackstore-pilot` | companion + puertos | ready_for_sol_review, conector bloqueado |
| `storecore-pos-integration-contract-v1` (externo) | OpenAPI canónico StoreCore | **WIP** no approved |

## Antes de GitHub / release

El corte DCT histórico de PR #26 quedó supersedido por la aceptación de PR #61: PostgreSQL 16, backend JAR real, Angular, reinicio durable, autorización y recuperación 18/18; Verify #109 verde. El merge y CI no homologan el adapter StoreCore live, facturación, transportistas ni proveedores de pago. No existe tag, deploy ni publicación.

El WIP histórico [durable-cash-transaction-boundary](wip/20261006-durable-cash-transaction-boundary/meta.md) documenta la corrección core local que originalmente quedó con aceptación residual. Ese pendiente fue supersedido por PR #61: PostgreSQL 16, browser real y reinicio DCT ya están aceptados. El antecedente no cambia la baseline, no crea tag ni habilita publicación, y tampoco acredita homologación StoreCore/fiscal. Decisión inicial preservada: sin cambio de schema ni migración; rollback conserva historia y detiene escrituras si se vuelve a la versión con la brecha.

- [ ] Sol aprueba specs, modelo y tasks.
- [ ] StoreCore `storecore-pos-integration-contract-v1` con Sol GO (hoy **WIP no approved**).
- [ ] Secretos por referencia. No credenciales en el repo.
- [x] Remote GitHub creado por el titular: `https://github.com/AgustinFalcon/blackstore` (privado).
- [ ] Facturación homologada con su contrato, evidencia y responsables aprobados.
- [ ] Correo Argentino homologado con adapter oficial, credenciales por referencia y evidencia E2E.
- [ ] Crear `release/1.0` desde el `master` homologado; integrar StoreCore↔BlackStore sólo en ese carril.
