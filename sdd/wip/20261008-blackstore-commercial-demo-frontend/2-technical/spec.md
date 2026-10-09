# Arquitectura frontend demo

## Composition root

Agregar ruta lazy `/demo` antes del wildcard, con providers propios. Estructura orientativa `features/demo/{domain,application,infrastructure,presentation}`: dominio puro, casos de uso, puerto `DemoRepository`, adaptador local y componentes. Los providers se crean en el root demo y comparten estado entre sus rutas. No reutilizar clientes HTTP productivos, guards reales, colas de comandos, IndexedDB de recovery, cookies ni servicios de sesión del POS real. El demo no se selecciona implícitamente por caída de API.

El adaptador usa semilla determinista y estado en memoria durante navegación. Recargar reinicia y la interfaz lo comunica. Si se agrega persistencia de conveniencia posteriormente, requiere clave/schema exclusivo `blackstore-demo`, parser cerrado, reset propio y tests; nunca leer/escribir namespaces operativos. Ningún demo effect emite HTTP de negocio. Assets locales evitan dependencias externas para presentar.

## Modelo cerrado y fronteras

Reutilizar tipos puros existentes cuando su semántica aplica. Nuevos conjuntos finitos (`DemoScenario`, `DemoPaymentMethod`, `DemoSaleStatus`, `DemoCashStatus`, `DemoRole`, `DemoMovementKind`, `DemoViewState`) son clases con constructor privado, instancias estáticas, `Unknown` y único `fromWire` en borde. Etiquetas, permisos y transiciones viven en tipos/políticas de dominio, nunca switches de strings en templates/stores/tests. Fixtures ingresan por un mapper único. Unknown produce estado neutral y bloquea mutaciones dependientes; nunca imprime raw input.

Agregados: producto/variante y versión, carrito y líneas, cliente ficticio, caja/movimiento, venta con snapshots y eventos compensatorios. Dinero en enteros con formateador compartido. `DemoRepository` expone snapshots inmutables y operaciones de caso de uso: carrito, cobro, reversa, caja, ajustes, CRUD demo, reportes. Las vistas no mutan arreglos ni calculan reglas comerciales duplicadas.

Cobro es un objeto de flujo con pasos validar carrito → validar caja/stock/precio → confirmar medio → registrar venta/movimientos → producir recibo. Cada paso tiene una responsabilidad. Repetir el mismo command ID devuelve el recibo original; cambios posteriores no duplican stock/caja. Errores simulados ocurren antes del commit o se recuperan consultando el resultado del command ID. La transacción local publica un nuevo snapshot completo; no emitir estados intermedios inconsistentes.

## Fixtures y repositorio

Semilla con al menos 18 productos/variantes, 4 categorías, imágenes locales o fallback, stock normal/bajo/agotado/inactivo, precios/costos opcionales, 6 clientes ficticios, ventas con varios medios/fechas/estados, caja y movimientos coherentes. Fechas relativas a reloj inyectado. No copiar datos personales o secretos del comercio. Exportación CSV escapa separadores/comillas y neutraliza fórmulas; impresión usa vista de recibo y CSS print, descarga genera Blob revocable. No botones de exportar que descarguen fixtures ajenos a filtros.

## Compatibilidad y migración

Conservar `/`, `/sesion`, `/caja`, `/catalogo`, `/ticket`, `/ventas`, `/reportes`, sus guards y providers. Las URLs `/demo/*` jamás se redirigen al runtime comercial como recuperación silenciosa. Se puede agregar enlace visible «Ver demostración» al acceso existente, sin saltar autenticación real. No cambiar defaults backend ni migrations. Al integrar backend futuro, implementar otro adaptador tras contrato y SDD propio; los datos demo no se migran como ventas reales.

## Diseño y accesibilidad

CSS tokens y componentes coherentes para botón, campo, tabla, badge, card, modal/drawer y feedback. Desktop 1440/1280, tablet 768/1024, móvil 390/360. Carrito lateral en escritorio; móvil usa resumen fijo y vista de carrito sin tapar acciones. Sin overflow horizontal de página; tablas ofrecen presentación responsive o scroll rotulado. Targets de 44px, contraste AA, focus visible, orden lógico, labels/error asociados, botones nativos, encabezados de tabla, live region de resultados sin anuncio excesivo, escape/foco de retorno en modal. Respetar reduced motion. Icono acompañado de texto o nombre accesible.
