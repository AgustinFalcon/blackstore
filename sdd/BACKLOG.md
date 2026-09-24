# Backlog — BlackStore

## Bloqueantes

- TODO-001 [critical] Sol GO/NO-GO de `blackstore-pilot`.
- TODO-002 [critical] Sol GO de StoreCore `storecore-pos-integration-contract-v1` (OpenAPI canónico; WIP no approved). Hasta entonces solo puertos/DTOs/fixtures.
- TODO-003 [critical] Doble gate para `20260921-storecore-connector-adapter`: evidencia aceptada StoreCore `TASK-PIC-001..008`, SHA-256/version 1.0.0-draft bloqueado y GO Sol explícito del WIP. Ningún adaptador real antes.

- TODO-004 [medium] [partial] BSUX-ANG hecho en las 5 rutas existentes. Residual: POS-06/07/08 no son rutas (`/sesion`, `/caja/cierre`, `/ticket/:saleId` siguen NO-GO).

## Diferidos

- TODO-010 [medium] Devolución post-factura + NC ARCA presencial (titular/contador; no REVERSAL StoreCore).
- TODO-011 [low] Catálogo local cacheado desde StoreCore (hoy `variant_id` se carga a mano / lookup mínimo).
