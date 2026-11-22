# Evidencia local — BlackStore ferretería showcase

Fecha: 2026-11-22  
Rama: `feat/blackstore-ferreteria-showcase-20261122`  
Base: `master` (`ff22c12`)

## Dataset demostrable

- 80 productos ficticios, 80 SKUs únicos y 14 categorías.
- Imágenes HTTPS de presentación con carga diferida y fallback local tipado.
- 15 clientes ficticios con dominios `example.invalid` y teléfonos no reales.
- 24 ventas históricas: cuatro medios de pago cerrados, multilínea,
  descuentos y dos devoluciones.
- 40 movimientos iniciales de stock, caja abierta, ingresos, egresos y cierres
  históricos.
- Estados de stock normal, bajo, agotado e inactivo.

## Flujos ejercitados

Catálogo, búsqueda, filtros, detalle, CRUD demo, carrito, descuentos, cobro en
efectivo/tarjeta/transferencia/QR simulado, pagos mixtos, rechazo/reintento,
recibos, devolución, inventario, caja, reportes, CSV, roles, responsive y
accesibilidad de teclado.

## Gates locales

| Gate | Resultado |
| --- | --- |
| `npm run typecheck` | PASS |
| `npm run build` | PASS; demo lazy chunk 173.97 kB |
| `npm run test:demo:domain` | 80/80 PASS |
| `npm run test -- --no-progress` | 438/438 PASS |
| `npm run test:demo` | 53/53 PASS, incluido CDN caído → fallback local |

La ejecución del browser se realizó fuera del sandbox porque Chromium y las
imágenes HTTPS necesitan el entorno Windows completo. No se observaron llamadas
HTTP de negocio desde la superficie demo.

## Límites explícitos

Esta evidencia certifica únicamente el modo demostración local. No certifica
StoreCore live, Mercado Libre, Mercado Pago, ARCA, Correo Argentino ni ninguna
homologación externa. No se incorporaron credenciales, tokens ni PII real.

## Revisiones independientes

La primera pasada de bugs, seguridad y SDD encontró el mismo P2: las imágenes
remotas no activaban el fallback. Se incorporó un handler con guard contra
bucles en tarjetas y modal, más un E2E que aborta Unsplash. La segunda pasada de
los tres reviewers resultó **APPROVE**, sin P0–P3 pendientes.

El SHA exacto de commit/PR y los checks hospedados se anexan al cerrar BFS-04;
hasta entonces el estado es `ready_to_ship`.
