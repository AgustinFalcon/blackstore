# Functional Spec — `blackstore-frontend-ux-system-v1`

**Status:** `ready_for_build` · **Fecha:** 2026-09-29 · **El dueño autoriza las 8 rutas del inventario, incluido `/sesion`, `/caja/cierre` y `/ticket/:saleId`.**

## Problema

El piloto ya tiene consola Angular (`/`, `/caja`, `/catalogo`, `/ticket`, `/reportes`) como formularios funcionales, no como sistema visual de mostrador. StoreCore storefront no es esta UI. USER de BlackStore no es CUSTOMER de StoreCore.

## Objetivo

Documentar, diseñar (Stitch) e implementar las superficies POS del piloto. Las cinco rutas existentes se alinean al diseño. `/sesion`, `/caja/cierre` y `/ticket/:saleId` entran en esta tanda. El browser no llama a StoreCore.

## Inventario

Ver `1-functional/screen-inventory.md`.

## No alcance

StoreCore storefront P/C/U, conector HTTP real, emisión fiscal, favoritos, loyalty, tenancy, DEMO, secretos, MP en browser.

## AC

- AC-BSUX-1: tokens POS OS en todas las piezas; header `#0f172a`; acento `#1d4ed8` sólo CTA/focus.
- AC-BSUX-2: roles CASHIER / SUPERVISOR / OWNER / AUDITOR; nunca CUSTOMER StoreCore.
- AC-BSUX-3: banner persistente si entitlement ≠ ENABLED o integración StoreCore bloqueada.
- AC-BSUX-4: catálogo read-only (versión / imported / validUntil); offline bloquea venta nueva.
- AC-BSUX-5: ticket muestra snapshot SKU/nombre/original/effective/descuento; split collected vs fees.
- AC-BSUX-6: cada pantalla documenta default / loading / vacío / error+Reintentar / disabled / éxito (`screen-states.md`).
- AC-BSUX-7: dialogos canónicos en `dialogs.md`; envelope y `errorCode` en `copy-and-errors.md`.
- AC-BSUX-8: teclado/a11y en `accessibility.md`. `/sesion`, `/caja/cierre` y `/ticket/:saleId` se implementan en esta tanda. El browser llama solo a BlackStore en `:8081`.
