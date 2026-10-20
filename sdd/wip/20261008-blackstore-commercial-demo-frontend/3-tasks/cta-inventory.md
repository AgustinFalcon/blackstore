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

Cada CTA de dominio se vincula además a DemoAction y resultado permitido/denegado según la política funcional (E11), incluyendo cambio de perfil entre apertura y confirmación. SH-03/SC comprueban exclusivamente perfil ficticio; ninguna variante muestra identidad ni logout real. El acceso/salida de la superficie usa navegación documental y evidencia E01.

## Reconciliación local #48 — 2026-10-20

Source y casos ejecutados: [PR48-transplant-local-20261020.md](../evidence/PR48-transplant-local-20261020.md), browser `commercial-demo.spec.ts` y specs `demo-permissions`, `local-demo-repository`, `demo-wire-mapper`, `demo-queries`, `demo-csv`. Los grupos siguientes vinculan los controles implementados a la política; no declaran cobertura individual completa de cada variante del inventario inicial.

- SH/DB/VE/PR/IN/CL/RP: navegación, detalle, búsqueda y filtros consultan `DemoAction.Read`; CSV/descarga usan `Export`; impresión usa `Print`. El cuerpo de página Unknown no expone lecturas comerciales. SH cambio de perfil usa `ChangeRole`; entrada/salida son anchors documentales.
- PO: cantidades, agregar/quitar/vaciar usan `EditCart`; cliente usa `SelectCustomer`; aplicar/quitar descuento usa `Discount`. PA selección de medio es input traducido por mapper; confirmar vuelve a evaluar `Checkout` y `Discount` pendiente. Cancelar vuelve al carrito sin mutación.
- RE devolución usa `Reverse`; CA apertura usa `OpenCash`, ingreso/egreso `MoveCash` y arqueo/cierre `CloseCash`. Confirmaciones se autorizan al ejecutar.
- PR crear/editar/activar/desactivar usa `SaveProduct` y transición de `DemoProductStatus`; IN ajuste usa `AdjustStock`; CL crear/editar usa `SaveCustomer`. Cancelar cierra draft sin publicar snapshot.
- SC escenario/reinicio usa `Reset`; perfil usa `ChangeRole`. Unknown deniega; la recuperación explícita permite elegir un perfil conocido sin conceder una acción comercial.

Nuevos controles: selector «Rol ficticio del shell» (misma `ChangeRole`, permite probar pérdida de permiso con pago/formulario pendiente) y enlace «Acceder al POS real» (navegación documental E01). E11 PASS_LOCAL de matriz/flujo; revisión independiente de inventario/diseño pendiente.
