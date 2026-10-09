# Inventario de acciones verificables

Cada ID debe enlazarse en evidencia al selector accesible, caso, resultado y captura/test. Inventario inicial de especificación: implementación debe reconciliarlo con todos los controles renderizados; cero CTA sin entrada. Un control deshabilitado requiere explicación y condición de habilitación comprobada. Estado inicial de todos: NOT_RUN.

- SH-01..05: navegar todas las secciones; abrir/cerrar drawer; cambiar perfil ficticio; abrir escenarios; volver al dashboard.
- DB-01..04: iniciar venta; abrir/ver caja; ver stock bajo; navegar ventas desde métricas con filtro coherente.
- PO-01..10: buscar; filtrar categoría; agregar; aumentar; disminuir; quitar línea; vaciar con confirmación/cancelación; seleccionar cliente; aplicar/quitar descuento; ir a pago.
- PA-01..07: elegir cada medio; ingresar recibido; validar referencia; volver a carrito; confirmar una vez; reintentar fallo; cancelar conservando carrito.
- RE-01..05: abrir recibo; imprimir; descargar; nueva venta; revertir con motivo/confirmación y cancelación sin mutación.
- CA-01..07: abrir caja; filtrar movimientos; ingresar movimiento; registrar egreso; preparar arqueo; confirmar/cancelar cierre; imprimir resumen.
- VE-01..06: buscar; filtrar fecha/estado/medio; limpiar; paginar; abrir detalle; exportar resultado filtrado.
- PR-01..07: buscar/filtrar; alternar presentación si se ofrece; ver detalle; crear; editar; activar/desactivar; cancelar sin guardar.
- IN-01..04: filtrar bajo stock; ver movimientos; ajustar con motivo; cancelar sin mutar.
- CL-01..05: buscar; crear; editar; abrir historial; seleccionar ocasional.
- RP-01..04: cambiar período; consultar detalle; exportar filtrado; limpiar filtro vacío.
- SC-01..04: elegir cada escenario; confirmar/cancelar reemplazo; reiniciar; recuperar error simulado.

Estados transversales por pantalla: loading, éxito, vacío, error recuperable y restricciones pertinentes. No exigir loading artificial en cada operación instantánea; el escenario de latencia permite comprobar bloqueo de doble envío y feedback. Formularios prueban valores inválidos, submit por teclado, error asociado y recuperación.
