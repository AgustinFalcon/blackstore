# Functional Spec — `blackstore-frontend-ux-system-v1`

**Status:** `ready_for_sol_review` · **Fecha:** 2026-09-23 · **BSUX-ANG aplicado en 5 rutas existentes; no pixel-complete**

## Problema

El piloto ya tiene consola Angular (`/`, `/caja`, `/catalogo`, `/ticket`, `/reportes`) como formularios funcionales, no como sistema visual de mostrador. StoreCore storefront no es esta UI. USER de BlackStore no es CUSTOMER de StoreCore.

## Objetivo

Documentar y diseñar (Stitch) todas las superficies POS del piloto. BSUX-ANG volcó tokens/estados a `/`, `/caja`, `/catalogo`, `/ticket` y `/reportes`. Sin `/sesion`, `/caja/cierre` ni `/ticket/:saleId`. Conector HTTP live: NO-GO. ADP-001..010 + L3 son loopback/fixture; no desbloquean companion ni host real.

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
- AC-BSUX-8: teclado/a11y en `accessibility.md`. POS-06 y POS-08 son huecos de código; no se inventan rutas Angular hasta GO.
