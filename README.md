# BlackStore

POS presencial. Runtime y base **separados** de StoreCore.

**Estado:** `blackstore-pilot`, contrato StoreCore v1 y `20260921-storecore-connector-adapter` están `ready_for_sol_review` (no approved). El adaptador real requiere evidencia StoreCore + GO Sol propio. `blackstore-pos-core` (ISSUE/REVERSAL) está **superseded**. Sin tag, sin conector real.

## Qué hace

- Ticket de mostrador, caja, factura presencial (titular/contador).
- Consume stock de StoreCore por `/blackstore-integration/v1` (reserve/commit/release/GET/reconcile) **después** de Sol GO.

## Lectura canónica

1. `sdd/STATUS.md`
2. `sdd/PROJECT.md` y `sdd/PATTERNS.md`
3. `sdd/wip/20260921-blackstore-pilot/`
4. Contrato StoreCore: `../StoreCore/sdd/wip/20260921-storecore-pos-integration-contract-v1/`
5. Plan adaptador: `sdd/wip/20260921-storecore-connector-adapter/`

## Desarrollo

Hasta evidencia StoreCore `TASK-PIC-001..008` y GO Sol del contrato **y** del adapter WIP: skeleton, schema propio, puertos, DTOs y fixtures. Sin cliente HTTP productivo.
