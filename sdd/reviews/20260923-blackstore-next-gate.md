# Gate — qué queda de BlackStore

**Fecha:** 2026-09-23

## Frontend (este tramo)

Cerrado en **documentación SDD-kit**: inventario POS-01..08, DS, estados, dialogos, copy/envelope, a11y, arquitectura (huecos POS-06/08).  
Stitch: POS-01/02 hechos; DS + POS-03..08 en generación paralela.  
**No** volcado Angular. **No** `/sdd.finish`.

## Resto — GO actual (piloto)

`20260921-blackstore-pilot` TASK-001..015 con evidencia. Fixtures/simulador/local PG 5433. Sin cliente HTTP.

## Resto — NO-GO hasta Sol

| Ítem | Por qué |
|---|---|
| `20260921-storecore-connector-adapter` TASK-ADP-001.. | Falta evidencia StoreCore PIC-003..008 HTTP + GO Sol de este WIP |
| BSUX-ANG volcado Stitch | Requiere GO; POS-06/08 no existen en código |
| Fiscal adapter / emisión | ADR-005; titular/contador |
| Secretos, tag, deploy, live companion | AGENTS.md |

## Siguiente implementación autorizable

1. Cerrar IDs Stitch en `stitch-mapping.md` (sin Angular).
2. Cuando StoreCore tenga HTTP 200/304/409/410 evidenciado y Sol GO del adapter: TASK-ADP-001.
3. Volcado Angular sólo con GO BSUX-ANG.
