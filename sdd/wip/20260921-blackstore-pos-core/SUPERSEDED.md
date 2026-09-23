# Superseded — blackstore-pos-core ISSUE/REVERSAL

El WIP `20260921-blackstore-pos-core` quedó **superseded** el 2026-09-21.

**Reemplazo:**
- Companion/piloto: `../20260921-blackstore-pilot/`
- Contrato HTTP canónico: `../../StoreCore/sdd/wip/20260921-storecore-pos-integration-contract-v1/` (OpenAPI `1.0.0-draft`, prefijo `/blackstore-integration/v1`)

**No implementar** desde este corpus: ISSUE/REVERSAL, `allow_oversell`, `NO_STOCK` como protocolo StoreCore, `REVERSAL_NOT_ALLOWED`, GET `/internal/v1/inventory/balances/{variant_id}`, ni perforación de safety stock.

Ticket, caja y factura presencial de este WIP son evidencia histórica; el piloto vigente modela esos bounded contexts contra reserve/commit/release/GET/reconcile. Sol no debe dar GO de conector real a este WIP.
