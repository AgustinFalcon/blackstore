# Backlog — BlackStore

## Bloqueantes

- BSPD [resolved] [Paridad de superficies productivas](features/20261109-blackstore-productive-surface-data-parity/meta.md): manifest por dataset, procedencia cerrada, catálogo fixture explícito y aceptación HTTP/PostgreSQL real para todos los datasets BlackStore-owned. Cerrado en PR #61 con Verify #107 y tres reviews finales aprobadas.

- POSC [resolved] [Carrito y cobro explícito](wip/20261008-blackstore-pos-cart-explicit-payment/meta.md): backend y frontend quedaron integrados por PR #52–#59 y revalidados acumulativamente por PR #61. No acredita StoreCore/ML live.

- TODO-006 [resolved for BlackStore-owned datasets] DCT real PostgreSQL 16/JAR/Angular 18/18 en PR #61, con reinicio y tráfico HTTP/productivo para la aceptación positiva; las intercepciones `route.fulfill` quedan limitadas a inyección negativa deliberada de fallos y estados futuros. El journey dual StoreCore live continúa dentro del gate de integración TODO-002/003 y no se declara homologado.

- TODO-001 [critical] Sol GO/NO-GO de `blackstore-pilot`.
- TODO-002 [critical] Sol GO de StoreCore `storecore-pos-integration-contract-v1` (OpenAPI canónico; WIP no approved). Hasta entonces solo puertos/DTOs/fixtures.
- TODO-003 [critical] Doble gate para `20260921-storecore-connector-adapter`: evidencia aceptada StoreCore `TASK-PIC-001..008`, SHA-256/version 1.0.0-draft bloqueado y GO Sol explícito del WIP. Ningún adaptador real antes.

- TODO-004 [integrado] Rutas operativas `/caja/cierre` y `/ticket/:saleId` incorporadas por PR #57 sobre master y posteriormente cubiertas por los cierres frontend PR #58/#59.
- TODO-007 [resolved] Caja/reportes BlackStore-owned con PostgreSQL, ledger, arqueo/diferencia, períodos SHIFT/DAY, timezone y filtros revalidados por DCT 18/18 en PR #61.

## Cortes integrados y aceptación residual

- BSOS [integrado] [Selección operativa de ventas](features/20261109-blackstore-sales-operation-selection/meta.md): selección explícita y durable desde `/ventas`, panel accesible con fases cerradas, retry honesto y navegación GET-only. Cerrado en PR #63, Verify #42725213277 y merge `76fd688`; frontend-only, sin endpoints ni homologaciones nuevas.

- Frontend POSC/ODR [integrado] PR #53–#59 incorporan POSC-T/U/E, rechazo fail-closed, detalle operativo y cierre final de presentación. PR #60 migra `upload-artifact` a Node.js 24. Estos merges no acreditan StoreCore live, fiscal, homologación ni deploy.

- TODO-000 [integrado] Identidad staff local fail-closed incorporada por PR #24 (`4055b28`); WIP `20261006-blackstore-staff-identity` documenta aceptación browser/PostgreSQL, sesión opaca, cookie/CSRF y RBAC/ownership. No equivale a homologación.
- TODO-005 [integrado] Runtime comercial durable incorporado en `e6a47a3`; WIP `20261006-blackstore-durable-commercial-runtime` documenta 9/9 tareas y aceptación browser/PostgreSQL/reinicio. Recovery comercial integrado no acredita caja contable ni integraciones live.
- DCT [resolved for local BlackStore runtime] La aceptación parcial de PR #26 fue supersedida por PR #61: PostgreSQL 16, JAR real, Angular, reinicios y negativos 18/18; Verify #109 verde. No acredita el adapter StoreCore live ni homologaciones externas.

## Diferidos

- TODO-010 [medium] Devolución post-factura + NC ARCA presencial (titular/contador; no REVERSAL StoreCore).
- TODO-011 [low] Catálogo local cacheado desde StoreCore (hoy `variant_id` se carga a mano / lookup mínimo).
