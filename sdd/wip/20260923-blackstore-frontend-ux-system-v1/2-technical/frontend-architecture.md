# Arquitectura frontend (objetivo)

Hoy el piloto usa componentes standalone con `HttpClient` directo. El volcado Stitch (BSUX-ANG, Sol GO) debe acomodarse a Angular 22 Clean Architecture + estado explícito:

```
container (ruta) → view (HTML Stitch) → store → use-case → HTTP
```

## Rutas actuales vs canónicas

| Canónica | Código hoy | Nota |
|---|---|---|
| POS-01 `/` | `PosShellComponent` | Health only |
| POS-02 `/caja` | `CashSessionComponent` | Abrir/cerrar/gasto juntos |
| POS-03 `/catalogo` | `CatalogPanelComponent` | Version/stale + tabla fixture SKU/variant; no pixel-complete |
| POS-04 `/ticket` | `SaleTicketComponent` | Formulario único reserva+split |
| POS-05 `/reportes` | `ShiftReportComponent` | Turno + día |
| POS-06 `/sesion` | **no existe** | Documentada; no implementar sin GO |
| POS-07 `/caja/cierre` | embebido en POS-02 | Se queda en `/caja` hasta un gate de rutas aparte. No extraer. |
| POS-08 `/ticket/:saleId` | **no existe** | Documentada; no implementar sin GO |

## Prohibido en el volcado

NgRx no es requisito del piloto actual; si se agrega store, sigue siendo local al POS. No CUSTOMER guards. No SDK MP. No llamada a `/blackstore-integration/v1`.
