# Entrega D/B/T/U/E

Todos los estados iniciales PLANNED; no confundir especificado con implementado. El usuario autoriza frontend simulado antes del backend.

1. **D — contrato y diseño**: este WIP, inventario CTA, diseño del shell/POS/pago/caja, revisión de diseño independiente. Salida: rutas/acciones y estados acordados, sin backend requerido.
2. **B — boundary demo**: composition root lazy, puerto y repositorio stateful, fixtures/clock, aislamiento de red y de persistencia. B significa límite frontend demo en este corte; backend real queda fuera. Depende D.
3. **T — tipos y casos de uso**: tipos cerrados/mappers, dinero, carrito, venta idempotente, caja/stock/reportes consistentes, Unknown. Depende B.
4. **U — UI completa**: shell/POS/pago/recibo primero; caja/ventas/productos/inventario/clientes/reportes/escenarios después. Implementaciones paralelas sólo con archivos y puertos delimitados. Depende T; capturas de escritorio y móvil por área.
5. **E — evidencia y corrección**: inventario de cada CTA visible vinculado a test o recorrido browser; validar escenarios, teclado/responsive, revisión Sol funcional y diseño independiente, corregir/revisar hasta sin hallazgos bloqueantes. Depende U. Build y tests del frontend operativo conservados.

Cada corte lleva commit verificable. PRs pueden apilarse D → B/T → U → E o agruparse si siguen siendo revisables; ninguna etiqueta DONE sin evidencia del commit. No publicar ni mergear como parte de esta tarea documental. No habilitar integración de producción para aprobar demo.

Revisión de diseño exige abrir la aplicación y capturar rutas reales a 1440 y 390 px: densidad, alineación, copy, jerarquía, controles, vacíos y responsive. Una lectura de TypeScript no cuenta como validación visual. Revisión funcional recorre cada CTA contra su resultado observable, incluyendo errores. Después de cambios visuales, repetir sólo rutas afectadas y smoke global.
