# Arquitectura frontend (objetivo)

El volcado usa container de ruta, view, store local del POS, use case y HTTP. NgRx no entra.

```
container (ruta) → view (HTML Stitch) → store → use-case → HTTP
```

## Rutas actuales vs canónicas

| Canónica | Código | Nota |
|---|---|---|
| POS-01 `/` | `HomeContainerComponent` | Health, catálogo, caja y atajos |
| POS-02 `/caja` | `CashContainerComponent` | Abrir, gasto y cierre en diálogo |
| POS-03 `/catalogo` | `CatalogContainerComponent` | Proyección de solo lectura |
| POS-04 `/ticket` | `TicketContainerComponent` | Reserva, cobro, commit y release en el simulador |
| POS-05 `/reportes` | `ReportsContainerComponent` | Turno y día |
| POS-06 `/sesion` | `SessionContainerComponent` | Elige USER y rol. No hay login HTTP |
| POS-07 `/caja/cierre` | `CashCloseContainerComponent` | Arqueo con lo que esta consola registró |
| POS-08 `/ticket/:saleId` | `TicketReadContainerComponent` | Snapshot local + GET de estado |

## Huecos

No hay `POST` de login en `:8081`. `/sesion` guarda el actor en la consola y el interceptor manda `X-Actor-Id` y `X-Role`. No crea usuarios y no llama a StoreCore.

`GET /sales/{operationId}` devuelve estado, recibo y referencia. No devuelve líneas ni la cuádruple. `/ticket/:saleId` muestra el snapshot guardado en esta consola y refresca el estado con ese GET. Un `saleId` que esta consola no registró queda vacío.

No hay listado HTTP de movimientos de caja. El arqueo usa la apertura, los gastos y los cobros en efectivo registrados en esta consola.

## Prohibido en el volcado

NgRx no es requisito del piloto actual; si se agrega store, sigue siendo local al POS. No CUSTOMER guards. No SDK MP. No llamada a `/blackstore-integration/v1`.
