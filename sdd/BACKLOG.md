# Backlog — BlackStore

## Bloqueantes

- POSC [high, proposed] [Carrito y cobro explícito](wip/20261008-blackstore-pos-cart-explicit-payment/meta.md): reserva multiline backend-first, compatibilidad fingerprint/journal v1 y aceptación PG16/browser propia. D en revisión; B/T/U/E planned. No UI-only ni StoreCore/ML live.

- TODO-006 [critical] Harness E2E real StoreCore+BlackStore sin `route.fulfill`, con dos PostgreSQL y prueba de reinicio.

- TODO-001 [critical] Sol GO/NO-GO de `blackstore-pilot`.
- TODO-002 [critical] Sol GO de StoreCore `storecore-pos-integration-contract-v1` (OpenAPI canónico; WIP no approved). Hasta entonces solo puertos/DTOs/fixtures.
- TODO-003 [critical] Doble gate para `20260921-storecore-connector-adapter`: evidencia aceptada StoreCore `TASK-PIC-001..008`, SHA-256/version 1.0.0-draft bloqueado y GO Sol explícito del WIP. Ningún adaptador real antes.

- TODO-004 [medium] [partial] BSUX-ANG incorporado; `/sesion` ya es ruta con identidad staff (PR #24). Residual: `/caja/cierre` y `/ticket/:saleId` no son rutas dedicadas; su diseño/aceptación no se cierra por DCT.
- TODO-007 [high] Caja/reportes: ledger append-only productivo, arqueo/diferencia y períodos SHIFT/DAY reales con timezone y filtros.

## Cortes integrados y aceptación residual

- TODO-000 [integrado] Identidad staff local fail-closed incorporada por PR #24 (`4055b28`); WIP `20261006-blackstore-staff-identity` documenta aceptación browser/PostgreSQL, sesión opaca, cookie/CSRF y RBAC/ownership. No equivale a homologación.
- TODO-005 [integrado] Runtime comercial durable incorporado en `e6a47a3`; WIP `20261006-blackstore-durable-commercial-runtime` documenta 9/9 tareas y aceptación browser/PostgreSQL/reinicio. Recovery comercial integrado no acredita caja contable ni integraciones live.
- DCT [partial acceptance] PR #26 MERGED (head `caab0a9`, merge `a9887a3`), dual exact-head Sol APPROVED, CI PR `37634224683` y master `37634797108` verdes. Local 153 tests/39 suites/cero failures/errors/skips con seis clases Docker-only excluidas; PG18. Pendientes PG16 y browser real con reinicio DCT; sin archivo, /sdd.finish ni homologación.

## Diferidos

- TODO-010 [medium] Devolución post-factura + NC ARCA presencial (titular/contador; no REVERSAL StoreCore).
- TODO-011 [low] Catálogo local cacheado desde StoreCore (hoy `variant_id` se carga a mano / lookup mínimo).
