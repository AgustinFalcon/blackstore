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

## Corrección de review UX P2 — carrito bajo filtros sticky

`Ver carrito` ahora mide la altura real de la barra de búsqueda/filtros y aplica
ese alto + 16 px como `scroll-margin-top` antes de hacer scroll y devolver foco.
No depende de un alto fijo de toolbar: se adapta a los filtros y al viewport.

Regresión Chromium a 390 y 768 px exige encabezado, primera línea y controles de
cantidad completamente dentro del viewport, con sus bordes superiores debajo del
toolbar y los controles encima del resumen fijo. Capturas `390-cart-focused.png`
y `768-cart-focused.png` regeneradas e inspeccionadas. Suite completa 18 escenarios
PASS (18.1 s), 27 dominio PASS, typecheck/build PASS. Lazy demo 115.87 kB; inicial
498.46 kB. Listener detenido y W: removida. Revisión independiente de este ajuste
aún PENDING.

## Corrección de auditoría final — navegación de stock y trazabilidad CTA

Stock bajo del dashboard ahora navega a `/demo/inventario?stock=low`. Contador,
checkbox y grilla comparten `DemoStockFilter.LowActive`: activos con stock<=3.
Browser exige mismos 5 productos y sus cantidades; excluye inactivos; desmarcar
restaura 18. Domain valida límites3/4, exclusión de inactivo y Unknown neutral.

Se reemplazó el inventario inicial de grupos/NOT_RUN por 149 registros concretos
ID→ruta→selector→escenario→resultado→test→PASS, con sufijos de instancia SKU,
cliente y venta. La suite CTA recorre 54 detalles, las acciones de cada producto,
ocho recibos y siete clientes; también cubre filtros completos, modal cancelar/
confirmar, escenarios6, perfiles2 y denegaciones Cajero que conservan estado.

Este recorrido detectó un fallo adicional real: `href="#demo-main"` resolvía contra
`base href="/"` y sacaba al usuario del demo. El enlace ahora usa la URL actual con
fragmento Angular y enfoca `main` (tabindex=-1). Navegar después conserva demo.

Resultado final: typecheck PASS; 28 domain specs PASS; build PASS (498.46 kB
inicial, 116.71 kB demo); 31 escenarios Chromium PASS, 38.7 s, tres workers.
La primera expansión falló por selectores exactos que incluían opciones de select;
se corrigieron sin relajar assertions de resultado. La siguiente detectó el fallo
del skip link; tras corregir producto pasó la suite completa. No se hereda aprobación
previa: re-review de este SHA PENDING. Sin push/merge/APIs reales. Listener4215 ausente
y sin unidades subst tras el teardown.
