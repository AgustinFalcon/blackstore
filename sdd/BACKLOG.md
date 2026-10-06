# Backlog — BlackStore

## Bloqueantes

- TODO-000 [critical] Identidad BlackStore real: sesión opaca persistida, cookie/CSRF, RBAC y ownership; eliminar autoridad desde `X-Actor-Id`/`X-Role`. WIP `20261006-blackstore-staff-identity`.
- TODO-005 [critical] Runtime durable: rehidratación PostgreSQL, atomicidad intención/outbox/proyección y worker de recovery tras reinicio.
- TODO-006 [critical] Harness E2E real StoreCore+BlackStore sin `route.fulfill`, con dos PostgreSQL y prueba de reinicio.

- TODO-001 [critical] Sol GO/NO-GO de `blackstore-pilot`.
- TODO-002 [critical] Sol GO de StoreCore `storecore-pos-integration-contract-v1` (OpenAPI canónico; WIP no approved). Hasta entonces solo puertos/DTOs/fixtures.
- TODO-003 [critical] Doble gate para `20260921-storecore-connector-adapter`: evidencia aceptada StoreCore `TASK-PIC-001..008`, SHA-256/version 1.0.0-draft bloqueado y GO Sol explícito del WIP. Ningún adaptador real antes.

- TODO-004 [medium] [partial] BSUX-ANG hecho en las 5 rutas existentes. Residual: POS-06/07/08 no son rutas (`/sesion`, `/caja/cierre`, `/ticket/:saleId` siguen NO-GO).
- TODO-007 [high] Caja/reportes: ledger append-only productivo, arqueo/diferencia y períodos SHIFT/DAY reales con timezone y filtros.

## Diferidos

- TODO-010 [medium] Devolución post-factura + NC ARCA presencial (titular/contador; no REVERSAL StoreCore).
- TODO-011 [low] Catálogo local cacheado desde StoreCore (hoy `variant_id` se carga a mano / lookup mínimo).
