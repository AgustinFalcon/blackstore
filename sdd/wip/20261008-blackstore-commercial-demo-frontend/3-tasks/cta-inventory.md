# Inventario reconciliado de CTAs — 2026-10-09

Source: demo-shell.component.ts y demo-page.component.html. Formato de cada registro:
ID | ruta (prefijo /demo) | selector | escenario → resultado visible | test | estado.
Las instancias repetidas se identifican por `{route}`=pos/productos/inventario,
`{sku}`=BS-001..018, `{saleId}` y `{customerId}`. El test CAT-FILTER/DETAIL recorre
54 detalles y todos los controles de producto por fila, incluidos disabled.
Ventas/clientes usan handlers parametrizados por identidad y varias filas/estados.
Los sufijos son IDs de instancias concretas, no rangos ambiguos de acciones.

`all sale and customer row actions` recorre las ocho ventas y siete clientes,
incluyendo edición/guardado, apertura/cierre de historial y venta por cliente.
Alias `electronic pending`: tres tests `Tarjeta: pending`, `Transferencia: pending`,
`QR: pending`. `payment-scenarios` y `suites` señalan los archivos listados abajo.

Tests en `frontend/e2e/demo/{commercial-demo,payment-scenarios,cta-coverage}.spec.ts`;
se cita el prefijo único del nombre. PASS significa ejecución local del frontend
demo; review/CI remoto y APIs reales no se atribuyen. Campos de formulario también
se registran para que cada control visible tenga trazabilidad.

## Shell y dashboard

- SH-SKIP | todas | .skip | teclado→misma ruta/query/#demo-main/main enfocado | SH-NAV/DB-LINK + SH-SKIP preserves low-stock | PASS.
- SH-BRAND | todas | link BlackStore COMERCIO | navegar→dashboard | SH-NAV/DB-LINK | PASS.
- SH-NAV-HOME | todas | nav/link Inicio | navegar→/demo | SH-NAV/DB-LINK | PASS.
- SH-NAV-POS | todas | nav/link Nueva venta | navegar→/pos | SH-NAV/DB-LINK | PASS.
- SH-NAV-SALES | todas | nav/link Ventas | navegar→/ventas | SH-NAV/DB-LINK | PASS.
- SH-NAV-PRODUCTS | todas | nav/link Productos | navegar→/productos | SH-NAV/DB-LINK | PASS.
- SH-NAV-INVENTORY | todas | nav/link Inventario | navegar→/inventario | SH-NAV/DB-LINK | PASS.
- SH-NAV-CUSTOMERS | todas | nav/link Clientes | navegar→/clientes | SH-NAV/DB-LINK | PASS.
- SH-NAV-CASH | todas | nav/link Caja | navegar→/caja | SH-NAV/DB-LINK | PASS.
- SH-NAV-REPORTS | todas | nav/link Reportes | navegar→/reportes | SH-NAV/DB-LINK | PASS.
- SH-NAV-SCENARIOS | todas | nav/link Escenarios | navegar→/escenarios | SH-NAV/DB-LINK | PASS.
- SH-DRAWER-OPEN | móvil | button Abrir navegación | 390px→drawer/foco | mobile navigation traps focus | PASS.
- SH-DRAWER-CLOSE | móvil | button Cerrar menú | abierto→oculto | SH-NAV/DB-LINK | PASS.
- SH-DRAWER-ESC | móvil | Escape | abierto→foco trigger | mobile navigation traps focus | PASS.
- SH-HEADER-CASH | todas | .cash-pill | cerrada→/caja | empty navigation and closed navigation | PASS.
- SH-BANNER-SCENARIOS | todas | link Escenarios y reinicio | navegar→/escenarios | product/customer CRUD | PASS.
- SH-ERROR-CLOSE | error | button Cerrar error | Cajero→error desaparece | ROLE-RESTRICTIONS | PASS.
- DB-PAGE-NEW | /,/ventas | heading/link Nueva venta | normal→POS | retry, cancel, card payment | PASS.
- DB-HERO | / | link Comenzar una venta | normal→POS | SH-NAV/DB-LINK | PASS.
- DB-SALES | / | link Ventas registradas | normal→ventas | SH-NAV/DB-LINK | PASS.
- DB-REPORT | / | link Ventas netas | normal→reportes | SH-NAV/DB-LINK | PASS.
- DB-CASH | / | link Efectivo en caja | normal→caja | SH-NAV/DB-LINK | PASS.
- DB-LOW | / | link Stock bajo | normal→inventario?stock=low/mismos5activos<=3 | DB-LOW | PASS.
- DB-RECENT-LIST | / | link Ver ventas | normal→ventas | SH-NAV/DB-LINK | PASS.
- DB-RECENT-{saleId} | / | .list-row | fila→recibo | SH-NAV/DB-LINK | PASS.
- DB-FIRST | / | link Crear la primera | Sin ventas→POS | empty navigation and closed navigation | PASS.
- DB-SHORT-PRODUCT | / | link Explorar productos | normal→productos | SH-NAV/DB-LINK | PASS.
- DB-SHORT-CUSTOMER | / | link Registrar cliente | normal→clientes | SH-NAV/DB-LINK | PASS.
- DB-SHORT-CASH | / | .shortcut Revisar/Abrir caja | abierta/cerrada→caja | SH-NAV/DB-LINK + empty navigation | PASS.

## Productos, POS e inventario

- CAT-SEARCH-{route} | /{route} | label Buscar producto | match/no match→cards/vacío | CAT-FILTER/DETAIL | PASS.
- CAT-CATEGORY-{route}-{category} | /{route} | label Categoría | 4/todas→8/3/5/2/18 cards | CAT-FILTER/DETAIL | PASS.
- CAT-LOW-{route} | /{route} | label Stock bajo o agotado | checked→5 activos<=3 | CAT-FILTER/DETAIL | PASS.
- CAT-CLEAR-{route} | /{route} | button Limpiar | filtros→18cards | CAT-FILTER/DETAIL | PASS.
- CAT-EMPTY-CLEAR-{route} | /{route} | button Limpiar filtros | vacío→18cards | CAT-FILTER/DETAIL | PASS.
- CAT-DETAIL-{route}-{sku} | /{route} | .product-art | cada18→dialog | CAT-FILTER/DETAIL | PASS.
- CAT-DETAIL-CLOSE-{route}-{sku} | /{route} | dialog/button Cerrar | cada54→cerrado | CAT-FILTER/DETAIL | PASS.
- CAT-DETAIL-ADD-{sku} | /pos,/productos | dialog/button Agregar al carrito | disponible→línea | PO-CART/MODAL | PASS.
- PO-ADD-{sku} | /pos | card/button Agregar | cada disponible→línea | CAT-FILTER/DETAIL | PASS.
- PO-DISABLED-{sku} | /pos | card/primary disabled | agotado/inactivo→sin acción | CAT-FILTER/DETAIL | PASS.
- PO-INCREASE-{sku} | /pos | button Aumentar cantidad | 1→2 | PO-CART/MODAL | PASS.
- PO-DECREASE-{sku} | /pos | button Disminuir cantidad | 2→1 | PO-CART/MODAL | PASS.
- PO-QUANTITY-{sku} | /pos | label Cantidad de producto | ingresar2→subtotal25000 | PO-CART/MODAL | PASS.
- PO-REMOVE-{sku} | /pos | button Quitar producto | cada línea→vacío | CAT-FILTER/DETAIL | PASS.
- PO-CUSTOMER | /pos | label Cliente | Ana/ocasional→selección/reset | PO-CART/MODAL | PASS.
- PO-DISCOUNT-FIELD | /pos | label Descuento autorizado (%) | 10/0→borrador | PO-CART/MODAL | PASS.
- PO-DISCOUNT-APPLY | /pos | button Aplicar descuento | manager/cashier→total/error | PO-CART/MODAL + ROLE-RESTRICTIONS | PASS.
- PO-EMPTY | /pos | button Vaciar carrito | con línea→modal | PO-CART/MODAL | PASS.
- PO-EMPTY-CANCEL | /pos | alertdialog/button Cancelar | vaciar→línea conservada | PO-CART/MODAL | PASS.
- PO-EMPTY-CONFIRM | /pos | alertdialog/button Confirmar | vaciar→cart0/clientec0/descuento0 | PO-CART/MODAL | PASS.
- PO-PAY | /pos | link Continuar al pago | carrito→resumen | multiline sale | PASS.
- PO-OPEN | /pos | cart/link Abrir caja | cerrada→apertura | empty navigation and closed navigation | PASS.
- PO-QUICK | /pos | button Ver carrito | 390/768→título/línea/qty visibles | mobile and tablet can reach cart | PASS.
- PR-CREATE | /productos | button Crear producto | manager→form | product/customer CRUD | PASS.
- PR-EDIT-{sku} | /productos | card/button Editar | cada18→form/save | CAT-FILTER/DETAIL | PASS.
- PR-ACTIVATE-{sku} | /productos | card/button Activar | inactivo→activo | CAT-FILTER/DETAIL | PASS.
- PR-DEACTIVATE-{sku} | /productos | card/button Desactivar | activo→inactivo | CAT-FILTER/DETAIL | PASS.
- PR-NAME | /productos dialog | label Nombre | crear/editar→borrador | product/customer CRUD | PASS.
- PR-SKU | /productos dialog | label SKU | único/duplicado→guardado/error | modal validation | PASS.
- PR-CATEGORY | /productos dialog | label Categoría | semilla→guardar | CAT-FILTER/DETAIL | PASS.
- PR-PRICE | /productos dialog | label Precio (ARS) | válido/rol→snapshot/error | operator creates + cashier can sell | PASS.
- PR-SAVE | /productos dialog | button Guardar producto | válido/invalid/rol→catálogo/error | product/customer CRUD + cashier can sell | PASS.
- PR-CANCEL | /productos dialog | button Cerrar/Escape | crear/editar→sin mutar | MODALS/CLIENTS | PASS.
- IN-OPEN-{sku} | /inventario | button Ajustar stock / historial | cada18→dialog | CAT-FILTER/DETAIL | PASS.
- IN-QUANTITY | /inventario dialog | label Ajuste de unidades | positivo/negativo→borrador | modal validation | PASS.
- IN-REASON | /inventario dialog | label Motivo | texto→borrador | operator creates | PASS.
- IN-SAVE | /inventario dialog | button Registrar ajuste | válido/invalid/rol→stock/error | modal validation + ROLE-RESTRICTIONS | PASS.
- IN-CANCEL-{sku} | /inventario dialog | button Cerrar | cada18 borrador→sin movimiento | CAT-FILTER/DETAIL | PASS.

## Pago y comprobantes

- PA-CASH | /pago | radio Efectivo | recibido→aprobado/vuelto | multiline sale | PASS.
- PA-CARD | /pago | radio Tarjeta | iniciar→terminal | Tarjeta: pending | PASS.
- PA-TRANSFER | /pago | radio Transferencia | iniciar→alias ficticio | Transferencia: pending | PASS.
- PA-QR | /pago | radio QR | iniciar→QR ficticio | QR: pending | PASS.
- PA-RECEIVED | /pago | label Efectivo recibido (ARS) | cash/mixto→vuelto | multiline sale + mixed QR | PASS.
- PA-REFERENCE | /pago | label Referencia opcional | explícita/vacía→referencia | electronic pending | PASS.
- PA-MIX | /pago | label Combinar con efectivo | QR→campos mixto | mixed QR | PASS.
- PA-MIX-AMOUNT | /pago | label Parte en efectivo (ARS) | 5000→saldo7500 | mixed QR | PASS.
- PA-CONFIRM | /pago | button Confirmar cobro | cada medio/error→venta/terminal/retry | payment-scenarios + retry, cancel | PASS.
- PA-DISABLED | /pago | button Confirmar cobro disabled | sin medio/caja→no cobro | multiline sale + closed navigation | PASS.
- PA-BACK | /pago | link Volver al carrito | pendiente→carrito conservado | pending navigation | PASS.
- PA-CANCEL-ROUTE | /pago | link Cancelar pago y conservar carrito | pendiente→cancel/cart | electronic pending | PASS.
- PA-APPROVE | /pago terminal | button Simular aprobación | cada electrónico→recibo/stock/caja | electronic pending + mixed QR | PASS.
- PA-REJECT | /pago terminal | button Simular rechazo | cada electrónico→retry/foco/carrito | electronic pending | PASS.
- PA-CANCEL | /pago terminal | button Cancelar intento | cada electrónico→retry/carrito | electronic pending | PASS.
- RE-PRINT | /ventas/{saleId} | button Imprimir | completa→print invocado | receipt navigation and cash print | PASS.
- RE-DOWNLOAD | /ventas/{saleId} | button Descargar comprobante | completa→V-101-demo.txt | retry, cancel, card payment | PASS.
- RE-NEW | /ventas/{saleId} | receipt/link Nueva venta | completa→POS | MODALS/CLIENTS | PASS.
- RE-REASON | /ventas/{saleId} | label Motivo de devolución | texto→borrador | multiline sale | PASS.
- RE-REVERSE | /ventas/{saleId} | button Registrar devolución | manager/cashier→modal/error | multiline sale + cashier can sell | PASS.
- RE-CANCEL | /ventas/{saleId} | alertdialog/button Cancelar | devolver→completa | MODALS/CLIENTS | PASS.
- RE-CONFIRM | /ventas/{saleId} | alertdialog/button Confirmar | devolver→Devuelta/stockrestaurado | multiline sale | PASS.
- RE-MISSING | /ventas/missing | link Volver a las ventas | inexistente→lista | SH-NAV/DB-LINK | PASS.

## Ventas, clientes, caja, reportes

- VE-SEARCH | /ventas | label Buscar | cliente/id/empty→filas | VE-FILTER/RP-PERIOD | PASS.
- VE-FROM | /ventas | label Desde | futuro→vacío | VE-FILTER/RP-PERIOD | PASS.
- VE-TO | /ventas | label Hasta | futuro→vacío | VE-FILTER/RP-PERIOD | PASS.
- VE-STATUS | /ventas | label Estado | completas/devuelta/todos→7/1/8 | VE-FILTER/RP-PERIOD | PASS.
- VE-METHOD | /ventas | label Medio | cash/card/transfer/QR→3/3/2/0 | VE-FILTER/RP-PERIOD | PASS.
- VE-CLEAR | /ventas | toolbar/button Limpiar filtros | activo→reset | VE-FILTER/RP-PERIOD | PASS.
- VE-EMPTY-CLEAR | /ventas | empty/button Limpiar filtros | vacío→lista | VE-FILTER/RP-PERIOD | PASS.
- VE-EXPORT | /ventas | button Exportar CSV | lista→ventas-demo.csv | product/customer CRUD | PASS.
- VE-DETAIL-{saleId} | /ventas | row/link Ver detalle | fila→recibo | receipt navigation and cash print | PASS.
- VE-PREV | /ventas | button Anterior | página2→1 | cash close/open | PASS.
- VE-NEXT | /ventas | button Siguiente | página1→2 | cash close/open | PASS.
- CL-NEW | /clientes | button Nuevo cliente | manager/cashier→form | product/customer CRUD + cashier can sell | PASS.
- CL-SEARCH | /clientes | label Buscar cliente | match/empty→filtro | MODALS/CLIENTS | PASS.
- CL-CLEAR | /clientes | button Limpiar | Ana→7clientes | customer clear | PASS.
- CL-EMPTY-CLEAR | /clientes | button Limpiar búsqueda | vacío→7clientes | MODALS/CLIENTS | PASS.
- CL-EDIT-{customerId} | /clientes | card/button Editar | noocasional→form | inventory adjustments, edit | PASS.
- CL-HISTORY-{customerId} | /clientes | card/button Ver historial | compras/vacío→historial | MODALS/CLIENTS + customer empty history | PASS.
- CL-HISTORY-CLOSE | /clientes dialog | button Cerrar | historial→cerrado | customer empty history | PASS.
- CL-HISTORY-SALE-{saleId} | /clientes dialog | .list-row | compras→recibo | MODALS/CLIENTS | PASS.
- CL-START | /clientes dialog | button Iniciar venta para este cliente | cliente→POS/selección | inventory adjustments, edit | PASS.
- CL-NAME | /clientes dialog | label Nombre | crear/editar→borrador | product/customer CRUD | PASS.
- CL-EMAIL | /clientes dialog | label Email opcional | ficticio→guardado | customer form fields | PASS.
- CL-PHONE | /clientes dialog | label Teléfono opcional | ficticio→guardado | customer form fields | PASS.
- CL-SAVE | /clientes dialog | button Guardar cliente | válido→card | product/customer CRUD | PASS.
- CL-CANCEL | /clientes dialog | button Cerrar | crear/editar→sin mutar | MODALS/CLIENTS | PASS.
- CA-FUND | /caja | label Fondo inicial (ARS) | cerrada→borrador | cash close/open | PASS.
- CA-OPEN | /caja | button Abrir caja | cerrada→abierta/nuevo turno | cash close/open | PASS.
- CA-KIND | /caja | label Tipo | ingreso/egreso→tipo | SC-ALL + product/customer CRUD | PASS.
- CA-AMOUNT | /caja | label Importe (ARS) | válido→borrador | product/customer CRUD | PASS.
- CA-REASON | /caja | label Motivo / categoría | texto→borrador | product/customer CRUD | PASS.
- CA-MOVE | /caja | button Guardar movimiento | manager/cashier→fila/error | SC-ALL + ROLE-RESTRICTIONS | PASS.
- CA-COUNT | /caja | label Efectivo declarado (ARS) | seed/postventa→pesos correctos | cash count starts in ARS | PASS.
- CA-DIFF-REASON | /caja | label Motivo si hay diferencia | diferencia→cierre permitido | cash close/open | PASS.
- CA-CLOSE | /caja | button Confirmar cierre | abierta→modal | cash close/open | PASS.
- CA-CANCEL | /caja | alertdialog/button Cancelar | cerrar→abierta | cash close/open | PASS.
- CA-CONFIRM | /caja | alertdialog/button Confirmar | manager/cashier→cerrada/error | cash count + ROLE-RESTRICTIONS | PASS.
- CA-FILTER | /caja | label Filtrar motivo | texto→sólofila | SC-ALL/ROLE/CA-FILTER | PASS.
- CA-PRINT | /caja | button Imprimir resumen | turnoactual→print | receipt navigation and cash print | PASS.
- RP-EXPORT | /reportes | button Exportar reporte | filtro→reporte-demo.csv | retry, cancel, card payment | PASS.
- RP-FROM | /reportes | label Desde | futuro→sinventas | VE-FILTER/RP-PERIOD | PASS.
- RP-TO | /reportes | label Hasta | futuro→sinventas | VE-FILTER/RP-PERIOD | PASS.
- RP-CLEAR | /reportes | button Limpiar filtros | vacío→completo | retry, cancel, card payment | PASS.
- RP-NEW | /reportes | link registrá una venta | vacío→POS | VE-FILTER/RP-PERIOD | PASS.
- RP-SALES | /reportes | link Explorar comprobantes | normal→ventas | SH-NAV/DB-LINK | PASS.

## Escenarios y modal transversal

- SC-NORMAL | /escenarios | Estado inicial/Comercio en marcha | reset→dashboard | SC-ALL | PASS.
- SC-CLOSED | /escenarios | Estado inicial/Caja cerrada | reset→cerrada | SC-ALL | PASS.
- SC-EMPTY | /escenarios | Estado inicial/Sin ventas | reset→vacío/CTA primera | SC-ALL | PASS.
- SC-LOW | /escenarios | Estado inicial/Stock bajo | reset→fixtures low | SC-ALL | PASS.
- SC-ERROR | /escenarios | Estado inicial/Error recuperable al cobrar | reset→error1/retry | retry, cancel, card payment | PASS.
- SC-SLOW | /escenarios | Estado inicial/Carga lenta | reset→loading→ready | SC-ALL | PASS.
- SC-LOAD | /escenarios | button Cargar escenario / reiniciar | cada6→modal | SC-ALL | PASS.
- SC-CANCEL | /escenarios | alertdialog/button Cancelar | cerrada solicitada→sigueabierta | SC-ALL | PASS.
- SC-CONFIRM | /escenarios | alertdialog/button Confirmar | cada6→reset | SC-ALL | PASS.
- SC-MANAGER | /escenarios | Rol ficticio/Encargada | selección→footer/permisos | SC-ALL | PASS.
- SC-CASHIER | /escenarios | Rol ficticio/Cajero | selección→footer/restricciones | SC-ALL + ROLE-RESTRICTIONS | PASS.
- SC-TRY | /escenarios | link Probar una venta | perfil→POS | ROLE-RESTRICTIONS | PASS.
- MODAL-ESC | modales | Escape | edit/error→cierra/foco | modal validation | PASS.
- MODAL-CONFIRM | confirmaciones | button Confirmar | cart/reset/cash/reversa→resultado específico | suites | PASS.
- MODAL-CANCEL | confirmaciones | button Cancelar | cart/reset/cash/reversa→snapshot conservado | suites | PASS.

No existen toggle de presentación ni método de envío en venta presencial: entrega
inmediata se explica en pago/recibo. Sin controles live ML ni pago real. El inventario
cubre únicamente demo; review independiente de este incremento sigue pendiente.
