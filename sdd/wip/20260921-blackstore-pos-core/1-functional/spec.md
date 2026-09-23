# Especificación funcional — BlackStore POS core

**Status:** `superseded` · **Fecha:** 2026-09-21  
**Feature:** `feat-20260921-blackstore-pos-core`  

<!-- superseded_by: sdd/wip/20260921-blackstore-pilot/1-functional/spec.md -->

**HISTÓRICO — NO IMPLEMENTAR.** ISSUE/REVERSAL, `allow_oversell` y perforación de safety stock **no** se implementan. Contrato vigente: StoreCore `storecore-pos-integration-contract-v1` (`/blackstore-integration/v1`). Companion vivo: `20260921-blackstore-pilot`. Ver `SUPERSEDED.md`.

## Problema

El mostrador vende, cobra y factura en **BlackStore**. StoreCore es el ledger de stock WEB/ML. Hay que descontar el pool compartido sin crear una venta online y sin copiar el ticket a StoreCore.

## Alcance

Ticket presencial, caja del turno, snapshot de precio local, operador USER, facturación por ticket / día / lote / a mano, cliente HTTP de inventario StoreCore, popup de oversell y cola de reposición.

## No alcance y compliance

No DDL ni endpoints en PostgreSQL StoreCore. No `store_id`. No secretos en repo. No ledger canónico ni `next_web_delivery_at` (los calcula StoreCore). No emisión ARCA online. No ventas ocultas, doble libro ni bypass fiscal. **Las ventas presenciales se registran y se facturan aquí.** Régimen: titular/contador.

## Invariante

Ventas StoreCore = WEB + ML. Ventas BlackStore = mostrador. Cruce = cantidades de `variant_id`, nunca importes.

## Identidades

Operador = USER BlackStore (`OPERATOR` / `MANAGER` / `OWNER`). No es StoreCore `CUSTOMER`.

## Ticket y estados

Líneas: `variant_id` (BIGINT `product_variants.id` StoreCore) + `qty > 0` + precio snapshot local. No cerrar sin líneas. Máximo un ISSUE exitoso por ticket. `CLOSED` inmutable; corrección = NC / ticket nuevo.

| Situación | Estado |
|---|---|
| StoreCore `RESERVED`/`COMMITTED` | `CLOSED` tras commit |
| StoreCore `INSUFFICIENT_STOCK` | `PENDING_STOCK` o cancelación; **sin** oversell StoreCore |
| Operador cancela (sin reserve/commit exitoso) | `CANCELLED` + release si había reserva |
| Anulado pre-factura | `CANCELLED` + release |
| Anulado post-factura | Devolución + NC. **No** release/commit extra de stock |

## Flujo

1. Persistir `PENDING_STOCK` **antes** de HTTP.
2. `POST /internal/v1/inventory/external-operations` (todo o nada).
3. Si `NO_STOCK` y `oversell_allowed`, popup exacto:
   `Las ventas online acapararon este producto. Entrega web/ML más próxima: <next_web_delivery_at>. Podés venderlo ahora y reponer antes de esa fecha.`
   Si la fecha es null: añadir `sin fecha prometida; reponer cuanto antes.`
4. Aceptar oversell → **nueva** `operation_key` y `allow_oversell: true` en esa línea.
5. Rechazar → `CANCELLED` o `PENDING_STOCK`. Quitar línea → clave nueva.
6. Facturar en BlackStore. El lote cierra contra la suma de tickets `CLOSED` del período.

## Oversell y alertas

Cola `oversell_committed` con `replenish_by`. Vencido sin cobertura: escala encargado → titular. Nunca muta órdenes WEB. Apagado: GET `available` StoreCore cubre qty; el ticket sigue `CLOSED`.

## Caja y factura

Cobros del turno vs tickets `CLOSED`; diferencia se registra. Documento fiscal BlackStore marca `invoiced_at`. Facturado ⇒ prohibido `REVERSAL`.

## Criterios

- AC-01: `PENDING_STOCK` durable antes del POST; retry misma clave.
- AC-02: `NO_STOCK` = cero líneas consumidas; quitar línea = clave nueva.
- AC-03: popup copia exacta; oversell = clave nueva.
- AC-04: `CLOSED` inmutable.
- AC-05: facturado ⇒ no `REVERSAL`; post-factura = NC.
- AC-06: cola escala; jamás muta WEB.
- AC-07: GET available apaga `oversell_committed`.
- AC-08: caja registra diferencia; lote = suma del período.
- AC-09: precio local puede ≠ web; StoreCore solo qty/`variant_id`.
- AC-10: operador ≠ CUSTOMER StoreCore.

## Gates

Sol GO de este feature y del ingest StoreCore. Sin GO: no código productivo, no secretos, no publicación.
