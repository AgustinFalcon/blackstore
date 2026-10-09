# Experiencia comercial

## Shell y dirección visual

Aplicación de mostrador en español rioplatense. Mantener base navy, superficies claras y acento azul del sistema existente; reforzar jerarquía, espaciado, iconos coherentes, números tabulares y densidad operativa. Sin paneles que impriman JSON, identificadores técnicos ni errores internos en el recorrido comercial. La evidencia técnica queda en rutas operativas existentes.

Shell persistente con marca BlackStore, navegación lateral, título/contexto, estado de caja y perfil de demostración. En tablet, rail compacto; en móvil, drawer con foco contenido y botón de apertura etiquetado. El banner «Datos de demostración» acompaña un acceso a escenarios/reinicio; nunca ocupa el foco de compra. Cada pantalla tiene título, acción principal, contenido útil y ayuda contextual breve. No navegación decorativa, botones sin efecto ni enlaces `#`.

## Rutas y pantallas

- `/demo`: resumen del día, ventas, ticket promedio, caja, stock bajo y actividad. Tarjetas enlazan listas filtradas; iniciar venta abre POS; abrir caja lleva al formulario.
- `/demo/pos`: búsqueda por nombre/SKU/código, categoría, productos con imagen o fallback cuidado, disponibilidad, precio y botón agregar. Carrito multilínea editable con cantidad, quitar, vaciar confirmado, selección de cliente, descuento autorizado simulado y totales. Escape cierra overlays sin perder carrito. Sin stock, cantidad inválida o caja cerrada impiden cobrar con explicación y salida útil.
- `/demo/pago`: resumen de compra, elección explícita Efectivo/Tarjeta/Transferencia, importe recibido y vuelto en efectivo, referencia no sensible opcional para medios electrónicos. Sin tarjeta ni credenciales reales. Validación accesible, volver conserva carrito. Confirmar procesa una sola vez y genera venta/comprobante; doble clic no duplica. Cancelar pago conserva carrito. Fallo simulado permite reintentar sin duplicar.
- `/demo/ventas/:saleId`: comprobante comercial claramente simulado, líneas/precios/descuento/medio/vuelto/fecha/cajero/cliente, imprimir, descargar comprobante y nueva venta. No usar etiqueta factura fiscal. Venta inexistente muestra estado recuperable. Reversa simulada con motivo y confirmación conserva venta original y agrega evento compensatorio; no elimina historial.
- `/demo/caja`: abrir con fondo inicial, movimientos con filtros, registrar ingreso/egreso categorizado con motivo, arqueo con declarado y diferencia, cierre confirmado con razón cuando corresponde y resumen imprimible. Importe inválido no muta. Caja cerrada mantiene lectura histórica y permite nueva apertura.
- `/demo/ventas`: búsqueda, fechas, estado, medio de pago, paginación, detalle y exportación CSV del conjunto filtrado; incluye estado vacío y limpiar filtros.
- `/demo/productos`: búsqueda/categoría/stock, lista o grilla consistente, detalle por drawer accesible. Crear/editar producto demo y activar/desactivar con validación SKU único/precio no negativo/campos requeridos. Mantener historial snapshot de ventas; cambios no alteran comprobantes previos. Catálogo operativo sigue propiedad de StoreCore: este editor existe sólo en datos simulados.
- `/demo/inventario`: existencias, stock bajo, detalle de movimientos, ajuste simulado con cantidad/motivo, historial auditable y filtros. Venta descuenta stock una vez; reversa restituye una vez. No permitir stock negativo. Mostrar claramente que no sincroniza Mercado Libre ni StoreCore.
- `/demo/clientes`: buscar, alta/edición demo, detalle e historial de compras. Validar campos y email si se informa. Cliente ocasional siempre disponible. Sólo identidades ficticias; ninguna credencial de acceso deriva de este listado.
- `/demo/reportes`: período, tarjetas calculadas desde estado demo, ventas por medio/categoría, productos más vendidos, egresos y neto. Tablas equivalentes accesibles acompañan gráficos. Exportar filtros actuales; no inventar margen cuando falta costo. Cambiar fechas recalcula; sin datos ofrece salida útil.
- `/demo/escenarios`: selector de fixture inicial, reinicio confirmado y lectura breve de qué cambia. Escenarios normal/caja cerrada/sin ventas/stock bajo/error recuperable/carga lenta. Cambiar escenario avisa que reemplaza datos demo. Reiniciar vuelve a semilla determinista sin tocar sesión ni persistencia real.

## Reglas de coherencia

Los flujos comparten una única fuente demo: una venta cambia ventas, caja, inventario, cliente y reportes. Totales y descuentos son los mismos en carrito, pago, recibo y reportes. Moneda ARS en unidades menores enteras; no acumular floats. El efectivo recibido debe cubrir total; vuelto no cuenta como ingreso. Caja cuenta efectivo, reportes separan todos los medios. Reversa registrada y egresos intervienen con signo correcto. No generar ventas con carrito vacío ni cantidad cero. Si el producto cambia mientras está en carrito, validar versión/stock/precio antes de confirmar y pedir revisar el resumen.

La simulación funciona sin API y sin autenticación real. Perfil ficticio de cajero/encargado con selección explícita para demostrar permisos; nunca representa una sesión autenticada ni inyecta headers reales. UI explica acciones restringidas y permite cambiar perfil en escenarios. No mostrar promesas de integración activa.
