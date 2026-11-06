# Spec funcional

## Resultado de negocio

BlackStore debe poder demostrarse de punta a punta como POS local y, al entrar al modo operativo, permitir que el personal encuentre productos, comprenda caja/ventas/reportes y mantenga contexto accesible sin interpretar primero IDs técnicos.

## Inventario cerrado

- Demo: inicio, POS, pago, ventas, comprobante, productos, inventario, clientes, caja, reportes y escenarios.
- Operativo: sesión, inicio, caja, cierre, catálogo, ticket activo, comprobante durable, ventas y reportes.
- No existe ni se promete experiencia de comprador: StoreCore es la tienda web; BlackStore es el POS/administración local.

## Criterios

1. Cada ruta tiene título descriptivo y enfoca el contenido al cambiar de página; filtros/query no roban el foco.
2. Catálogo y ticket permiten buscar localmente por nombre o SKU, limpiar cero resultados y operar por teclado.
3. Catálogo muestra moneda ARS, fechas legibles, resumen y estados de loading/vacío/error/reintento sin alterar la proyección autorizada.
4. Reportes prioriza métricas y deja zona, corte, snapshot e IDs como evidencia secundaria. Zona comercial sigue siendo explícita y nunca se infiere como autorización.
5. El POS conserva permisos, journal, recovery, precios/versiones y reserva autoritativa; la demo permanece sin HTTP, SID ni storage operativo.
6. 320/390/768/1440 px mantienen controles y contenido; Unknown nunca imprime wire crudo ni se convierte en un estado válido.
7. Los 50 recorridos demo, tres ODR, unitarias, dominio, tipos y build pasan sobre el SHA final; CI hospeda demo y ODR.

## Matriz resumida

| Pantalla | Capacidad visible | Evidencia requerida |
|---|---|---|
| `/demo/pos` + `/demo/pago` | carrito, descuento, cliente, efectivo/tarjeta/transferencia/QR simulados, mezcla y reintento | CTA, roles, estados y aislamiento sin IO |
| `/demo/ventas/:saleId` | comprobante, descarga, impresión, devolución | identidad estable y stock/caja coherentes |
| `/demo/productos`, inventario, clientes | CRUD simulado, activación, ajuste e historial | validación, permisos y resultados visibles |
| `/demo/caja`, reportes, escenarios | turnos, movimientos, arqueo, CSV, resets | repetibilidad y cálculos consistentes |
| `/catalogo`, `/ticket` | búsqueda y selección del catálogo autorizado | precio/versión preservados y reserva fail-closed |
| `/ventas`, `/ticket/:saleId`, `/caja/cierre`, `/reportes` | lectura/recovery/cierre/reportes | cero autoridad nueva y evidencia técnica disponible |
