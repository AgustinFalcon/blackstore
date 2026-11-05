# Addendum técnico — cierre frontend demostrable

Fecha: 2026-11-05. Base: `358b9bc`.

## Alcance

BlackStore es el POS presencial. El cierre cubre los perfiles ficticios Cajero y
Encargada y las superficies Inicio, Nueva venta, Pago, Comprobante, Caja, Ventas,
Productos, Inventario, Clientes, Reportes y Escenarios. No agrega una tienda pública,
un comprador autenticado ni un administrador productivo: esos conceptos pertenecen
a StoreCore o requieren otro incremento funcional.

La Demo continúa aislada de HTTP, SID, storage y proveedores. Ningún resultado de
este corte acredita POSC B/T/U/E, StoreCore live, facturación u homologación.

## Invariantes del último corte

1. Un cierre de caja es un snapshot inmutable consultable después de abrir el turno
   siguiente. Apertura, movimientos y cierre conservan identidad y cronología.
2. Todo importe individual y todo saldo agregado deben ser enteros seguros antes de
   publicar estado o consumir una secuencia.
3. Los reportes se derivan de snapshots históricos de líneas. Categoría e identidad
   comercial no se reconstruyen desde el catálogo mutable.
4. Medio de pago, estado, movimiento, escenario, rol, página y filtro finito llegan a
   la vista como tipos cerrados. El wire entra por un traductor y Unknown deniega.
5. Pantalla, descarga e impresión usan el mismo modelo de comprobante. El resumen de
   caja es un documento específico, no la impresión accidental del formulario.
6. Un error de campo expone asociación accesible y foco recuperable. Contraste, zoom
   200 %, targets y reflow se validan en móvil, tablet y escritorio.

## Decisiones de diseño

- Dashboard: período explícito “Hoy” y ticket promedio visible; los accesos preservan
  el mismo criterio temporal.
- Pago: precondiciones visibles junto al CTA y recuperación directa hacia apertura de
  caja o carrito.
- Caja: selector de turno/cierre, detalle histórico e impresión del resumen elegido.
- Productos/reportes: SKU/productId estable; nombres y categorías provienen del
  snapshot de venta.
- Responsive: el shell productivo y el Demo no mantienen columnas fijas por debajo de
  sus breakpoints.

## Gates

- Dominio, typecheck, build, Angular unit y browser completo sobre el HEAD final.
- Matriz CTA/ruta/rol/estado y revisión visual independiente con P0–P2 = 0.
- Accesibilidad automatizada y manual focal: teclado, foco, asociación de errores,
  contraste y zoom/reflow.
- E01 con SID/PostgreSQL real continúa separado y no bloquea la presentación Demo;
  permanece PARTIAL hasta existir ese entorno.

