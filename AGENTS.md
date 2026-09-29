# BlackStore: guía para agentes

Repositorio SDD del POS presencial. Un comercio, una VM, una PostgreSQL **propia** (no la de StoreCore) y un runtime aparte. No implementar shared tenancy, `store_id` ni TenantFilter.

Leer `sdd/STATUS.md`, `sdd/PROJECT.md`, `sdd/PATTERNS.md` y el WIP activo antes de actuar.

## Relación con StoreCore

StoreCore es dueño de catálogo, precios autorizados, costos autorizados y stock.
Contrato canónico (WIP, no approved):
`../StoreCore/sdd/wip/20260921-storecore-pos-integration-contract-v1/2-technical/api/blackstore-integration.openapi.yaml`
Path: `/blackstore-integration/v1` (reserve/commit/release/GET/reconcile).

WIP histórico `20260921-blackstore-pos-core` (ISSUE/REVERSAL) está **superseded**. `pos-sales-ingestion` está superseded.

BlackStore es dueño de ticket, caja, gastos, arqueos y reportes. Nunca copia el ticket a StoreCore. Nunca accede a la DB de StoreCore.

## Reglas críticas

- USER de BlackStore no es CUSTOMER de StoreCore.
- Persistir intención + cuádruple `(client_instance_id, device_id, sale_id, operation_id)` + outbox **antes** del HTTP.
- Timeout → GET misma cuádruple.
- `INSUFFICIENT_STOCK` / `CATALOG_VERSION_STALE` / `VALIDATION` → nuevo `X-Operation-Id`, mismo `X-Sale-Id`.
- `CONFLICT` → re-POST misma cuádruple.
- `IDEMPOTENCY_PAYLOAD_MISMATCH` / `EXPIRED` → conservar evidencia; nueva venta = nuevo ID.
- Sin oversell ni perforación de safety stock. `availableQuantity` es stock vendible.
- No ventas ocultas, doble libro ni bypass fiscal.
- Credencial StoreCore: referencia opaca. Conector real bloqueado hasta evidencia StoreCore `TASK-PIC-001..008`, digest/version canónico bloqueado y Sol GO del contrato v1 **y** de `20260921-storecore-connector-adapter`.
- Preservar cambios locales; no desplegar, secretos reales, tags ni publicación.

## Flujo multiagente

1. Sol revisa y propone gates.
2. Terra actualiza specs, plan, trazabilidad y docs.
3. Sol declara GO/NO-GO.
4. Luna implementa únicamente tareas con GO.

## Estados cerrados

Un estado, rol, medio de pago o paso de un flujo es un tipo cerrado (`enum` o clase sellada). En TypeScript, clase con constructor privado, instancias estáticas y `fromWire` en el borde. La vista no compara strings de estado. Un valor de red desconocido es el caso `Unknown` de ese tipo. Cada paso de un flujo es un objeto. El dominio no importa framework.
