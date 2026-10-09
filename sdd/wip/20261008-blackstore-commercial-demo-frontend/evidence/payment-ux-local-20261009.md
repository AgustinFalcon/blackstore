# Incremento mostrador y pagos — evidencia local 2026-10-09

Base `f52dac45`, branch `feat/demo-payment-ux`. Implementación demo aislada.
Contrato del incremento: [payment-ux-addendum](../2-technical/payment-ux-addendum.md).

## Resultado reproducible

- `npm run typecheck`: PASS (app y specs).
- `npm run test:demo:domain`: 26 specs PASS, 0 failures.
- `npm run build -- --preserve-symlinks`: PASS, initial 498.46 kB,
  lazy demo 115.69 kB. Compilación por unidad temporal W:, removida al terminar.
- `npm run test:demo`: 17 escenarios Chromium PASS, 18.0 s, dos workers.
  Sin solicitudes `/api/` ni puertos backend. Sin sesión ni conectores reales.
- Listener 4215: ausente después de la suite; teardown global detiene el servidor
  una vez terminados ambos archivos de tests. Sin unidades `subst` activas.

## Procesos y roles cubiertos

Encargada: carrito multilinea/cantidades/descuento/cliente; efectivo aprobado;
tarjeta, transferencia y QR pendientes→rechazados/cancelados→reintento→aprobados;
pago mixto efectivo+QR con saldo y vuelto; comprobante/descarga/impresión y entrega
inmediata; devolución única; catálogo crear/editar/activar; inventario ajuste e
historial; clientes crear/editar/historial; caja egreso/arqueo/cierre/reapertura;
ventas filtro/paginación/exportación; reportes filtros/CSV y reparto por medio.

Cajero: alta de cliente y venta aprobada; edición de catálogo y devolución muestran
denegación recuperable. Domain además verifica bloqueo de descuentos, ajustes,
egresos y cierre por rol. Unknown de rol/medio/resolución falla cerrado.

Un pago pendiente conserva cliente, carrito y descuento, protege sus mutaciones y
puede retomarse al navegar. Rechazo/cancelación no cambia productos, ventas ni caja;
no puede aprobarse después. Aprobación mueve stock/venta una sola vez. Cobro mixto
mueve sólo su parte de efectivo, incluso al devolver. Browser confirma la misma
venta en inventario/historial/ventas/reportes/caja, incluyendo importes exactos:
QR 7.500 ARS, efectivo vendido 63.200 ARS y efectivo en caja 113.200 ARS.

Recorrido adicional crea un artículo, carga 5 unidades, vende 3 por transferencia,
obtiene el comprobante y vuelve al inventario: quedan 2, aparece el badge de stock
bajo y el historial conserva carga inicial y venta.

## Diseño y accesibilidad

1440/768/390 px sin overflow de página. Búsqueda POS sticky a <=900 px, quick cart
accesible al agregar. Nueve ilustraciones SVG locales diferencian prendas,
pantalones, shorts, zapatillas, bolsos, gorra, cinturón, medias y productos nuevos;
todas cargan localmente. QR ilustrativo con leyenda demo sin URL de pago.
Terminal/pago rechazado reciben foco tras render; labels y decisiones explícitas;
modal y drawer conservan Escape, foco atrapado y retorno verificados en browser.

Capturas generadas (ignoradas por Git) en `frontend/demo-test-results/screenshots/`,
incluyendo `390-qr-pending.png`, `*-compact-pos.png`, recibo y quick-cart.
Inspección visual local realizada; aprobación independiente UX/producto aún
PENDING. CI remoto y runtime real no ejecutados para este incremento. No push,
merge, publicación, integración ML ni cobro real.

## Hallazgos de ejecución resueltos

La primera corrida encontró duplicación de anuncio de rechazo (dos regiones status);
se eliminó el anuncio redundante. El test de foco encontró que RAF podía ejecutarse
antes de insertar el feedback de rechazo; se cambió a `afterNextRender`. La corrida
final completa de 17 escenarios pasó después de ambas correcciones.

## Corrección de review funcional P2 — unidades de arqueo

La revisión independiente detectó que el valor inicial `declared` tomaba centavos
como ARS. Se corrigió la entrada a `expectedCash()/100`; el comando sigue haciendo
`Math.round(declared*100)` una sola vez en el borde de UI. El repositorio y sus
movimientos conservan dinero en centavos.

Regresión browser: caja semilla precarga 108.200 ARS y cierra sin motivo con
diferencia 0; después de vender 12.500 ARS precarga 120.700 ARS y también cierra con
diferencia 0. Domain verifica declared===expected antes/después de venta.
Revalidación completa: typecheck PASS; 27 domain specs PASS; build PASS (498.46 kB
inicial / 115.69 kB demo); 18 Chromium escenarios PASS, 14.8 s. Review de la
corrección aún PENDING. Servidor detenido y unidad W: removida nuevamente.
