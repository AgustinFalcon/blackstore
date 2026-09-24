# Stitch mapping — BlackStore POS UX v1

**Proyecto:** `projects/17616515208002773612`  
**Design system:** `assets/15311393927341436529`

| ID | Stitch | Nota |
|---|---|---|
| DS | `assets/15311393927341436529` | BlackStore POS OS |
| DS-00 | `b559d8ecade74d489a7186e6f558c389` | Primitivas: rail, header, banner, botones, campo, línea de ticket, keypad |
| DS-04 | `5b837f0cd8744c55aaa29f098e913437` | Diálogos: cierre, reversa, StoreCore bloqueado, INSUFFICIENT_STOCK, fiscal NOT_CONFIGURED (test) |
| DS-05 | `e41ce9ea026441be91924eca69192fb4` | Estados: loading, error+Reintentar, vacío, disabled, éxito |
| DS-06 | `b7ff653e1b1245e093df58b21b9f2313` | Tipo: Inter, Efectivo tabular, SKU mono, 4 roles |
| POS-01 | `1777992c488f4e4bac6cb804b5cab868` | Shell + banner StoreCore bloqueada |
| POS-02 | `28f5e15ce348464f8b884d4f31890bc7` | Caja apertura/cierre/gasto |
| POS-03 | `feb78c7b638a4bfdaa662e1c1c01a187` | Catálogo fixture RO; stale/offline deshabilita venta nueva |
| POS-04 | `8cfa2ee081cf4ab3accc4460d91a8f64` | Ticket local: líneas, cobrado/fees, reserva, commit/release, reversa |
| POS-05 | `2b73cf291e5644419259eb532c05c5c5` | Reportes turno+día; margen UNKNOWN sin costo |
| POS-06 | `15532b89f7634b2a8df438f7797a0d63` | Login USER BlackStore; sin auto-alta |
| POS-07 | `70923eab098a41ed870b68c61c59dbd0` | Arqueo append-only |
| POS-08 | `d2c9622640054c84a6a41fd299babd2f` | Snapshot read-only, cuádruple visible, saga local |

Hay otra pieza de catálogo en el mismo proyecto: `5874478ee0824017bbaaf33b01be8617` (título “BlackStore POS - Catálogo”). La fila POS-03 apunta a la pieza generada en este tramo.
