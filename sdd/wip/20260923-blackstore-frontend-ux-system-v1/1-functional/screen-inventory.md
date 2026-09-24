# Inventario canónico — consola POS BlackStore

Una pantalla = una ruta. Código piloto actual es formulario funcional, no baseline pixel. Stitch es referencia visual.

Estados globales: default / loading / vacío / error+Reintentar / disabled / éxito. Español rioplatense.

## Design system

| ID | Qué define |
|---|---|
| DS-00 | Rail, header navy, banner entitlement/StoreCore, botones, campo, ticket line, money, keypad |
| DS-04 | Dialogos: cerrar caja, reversa, StoreCore bloqueado, INSUFFICIENT_STOCK, fiscal NOT_CONFIGURED (test) |
| DS-05 | Loading, error, vacío, disabled, éxito |
| DS-06 | Inter, money tabular, SKU mono, 4 roles |

## Rutas

| ID | Ruta código | Guard | Anatomía |
|---|---|---|---|
| POS-01 | `/` | sesión | Shell: health, entitlement, “StoreCore integración bloqueada”, atajos caja/ticket |
| POS-02 | `/caja` | CASHIER+ | Abrir sesión (terminal, cajero, apertura). Cerrar con declarado + motivo auditado. Gasto categoría/importe/razón |
| POS-03 | `/catalogo` | sesión | Fixture read-only: SKU, nombre, version, imported, validUntil. Stale/offline = venta nueva disabled |
| POS-04 | `/ticket` | CASHIER+ sesión abierta | Líneas snapshot. Split collected/fees. Reservar (simulador). Commit / release. Reversa con motivo. Sin HTTP StoreCore |
| POS-05 | `/reportes` | OWNER/AUDITOR/SUPERVISOR | Turno y día: gross, dto, net, collected, fees, gastos, cash flow. Margen UNKNOWN sin costo |
| POS-06 | `/sesion` | no | Login USER BlackStore. Roles. Sin auto-alta. Distinto de `/customer/session` StoreCore |
| POS-07 | `/caja/cierre` | SUPERVISOR/OWNER | Arqueo: apertura, movimientos, declarado, diferencia, razón. Append-only |
| POS-08 | `/ticket/:saleId` | sesión | Ticket snapshot read-only. Cuádruple visible. Estado saga local |

## Fuera de este WIP

StoreCore P/C/U, conector HTTP, fiscal adapter, multi-sucursal, venta offline real.
