# Design system — BlackStore POS OS

Stitch: `assets/15311393927341436529`.  
Alineado a StoreCore OS; densidad de mostrador.

## Color

| Token | Hex | Uso |
|---|---|---|
| theme | `#0f172a` | Header / rail |
| theme-ink | `#f8fafc` | Texto sobre header |
| canvas | `#f4f4f5` | Fondo |
| surface | `#ffffff` | Ticket, tablas, forms |
| ink | `#18181b` | Texto |
| accent | `#1d4ed8` | Primary, focus |
| success / warning / danger / info | `#166534` / `#a16207` / `#991b1b` / `#1e3a8a` | Badges, no marca |

Light only. WCAG 2.2 AA. Focus outline 2px accent + offset 2px.

## Tipo

Inter only. Ops h1 1.375rem/650. Body 1rem/1.5. Tablas 0.875rem. Precios `tabular-nums` etiquetados **Efectivo**. SKU mono.

## Layout

Rail izquierdo (sesión, caja, catálogo, ticket, reportes). Columna de ticket ancha. Targets táctiles ≥44px. Teclado numérico visible en qty/importe.

## Prohibido

Amarillo ML, cuotas, FULL, DEMO, `store_id`, CUSTOMER StoreCore, logo MP, secretos, emisión fiscal, conector live.
