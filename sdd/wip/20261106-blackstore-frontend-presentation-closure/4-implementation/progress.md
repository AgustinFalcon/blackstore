# Progreso

## Base verificada

- `7382616`: PR #57 integrado; `1565561` (demo FFC) es ancestro.
- `typecheck`, `typecheck:demo`, `typecheck:dct`, build: PASS.
- Angular: 396/396 PASS; dominio demo: 80/80 PASS.
- Playwright demo: 50/50 PASS; ODR: 3/3 PASS.

Estos resultados son baseline local del 2026-11-06 y deben repetirse sobre el SHA final. Evidencias históricas POSC/ODR no se heredan como aprobación del nuevo corte.

## Reviews de diseño

Astra: sin P0/P1; demo presentable. P2: títulos/foco, descubrimiento operativo de productos, jerarquía comercial de lecturas y reportes demasiado técnicos. Sol: sin bypass ni estado mágico; P2 CI no ejecuta demo/ODR. Terra: STATUS y WIP anteriores desfasados respecto de PR #56/#57.

## Candidato implementado

- Rutas demo y operativas con título descriptivo y foco por cambio de pathname; query/fragment no roba foco.
- Búsqueda local por nombre/SKU en catálogo y ticket, sin alterar identidad, precio ni versión autorizados.
- Catálogo con resumen, ARS, fechas legibles, cero resultados y salida a nueva venta.
- Reporte con métricas primero, evidencia técnica desplegable, fecha y zona explícitas guiadas sin inferir autorización desde el dispositivo.
- CI `frontend-browser` instala Chromium y ejecuta `typecheck:demo`, 51 tests demo y tres ODR.

Regresión local del candidato: typechecks PASS, build PASS, 399/399 Angular, 80/80 dominio demo, 51/51 Playwright demo y 3/3 ODR. Inspección visual real desktop de `/demo`: jerarquía/navegación/foco correctos. CI hospedado pendiente.

Primera review exact-head detectó que el job browser necesitaba compilar su propio `dist` y que una búsqueda podía conservar seleccionado un producto ya invisible. Ambos se corrigieron antes del PR; los gates y reviews deben repetirse sobre el nuevo SHA.

La segunda review detectó una variante residual durante el refresco del catálogo con filtro persistente. El efecto y `selectedItem` ahora se restringen al catálogo visible y fallan cerrados; la regresión automatizada elimina el SKU filtrado durante el refresco. Astra UX/producto, Sol bugs/contratos y Sol seguridad aprobaron coordinadamente `db9ca9058320ddf498c4a94a771413e794ec12f0` sin P0–P3.

El primer CI del PR #58 dejó backend, frontend y frontend-browser verdes, pero DCT detectó tres incompatibilidades de contrato accesible introducidas por la presentación: el heading exacto de catálogo, ayudas incluidas dentro del nombre accesible de fecha/zona y filtros técnicos colapsados. Se restauraron nombres accesibles estables y visibilidad operativa sin retirar la jerarquía ni las ayudas del diseño. La regresión Angular fija estos contratos; CI debe repetirse sobre el nuevo SHA.

La segunda corrida DCT validó 17/18 casos y reveló que plegar toda la evidencia técnica ocultaba también el corte temporal esperado por la lectura diaria. El corte queda ahora como metadato comercial compacto siempre visible; el resto de la evidencia continúa dentro del detalle desplegable.

La tercera corrida hospedada cerró DCT en 18/18. `frontend-browser` conservó 50/51 casos verdes y el único escenario restante —cancelación por teclado tras degradar el rol con pago pendiente— reveló que el foco accesible programado para una navegación podía ejecutarse después de que la persona ya había avanzado a otro control. El shell ahora enfoca el contenido de la nueva ruta sólo si el foco continúa donde estaba al terminar la navegación o regresó al `body`; nunca roba el foco de una interacción posterior. La regresión comprueba el foco de cada control y envía cada tecla al locator enfocado; mantiene el recorrido exclusivo por teclado y todas sus aserciones, sin omitir casos, agregar reintentos ni serializar la suite. El SHA resultante debe repetir la evidencia completa y las tres reviews exact-head antes del merge.

La review coordinada de `dc861fc` encontró el mismo riesgo residual en el shell operativo. La regla se centraliza en `NavigationFocusPolicy`, se aplica tanto a demo como a POS operativo y queda cubierta por prueba unitaria de política, E2E de pago por teclado y ODR de navegación a reportes. Así ambas superficies preservan cualquier control alcanzado después de `NavigationEnd`.

La review exact-head de `8c59f56` aprobó bugs y seguridad, pero UX reprodujo un P2 de alcance CSS: el toolbar operativo imponía `flex-basis: 100%` sobre los filtros del demo y convertía la búsqueda de escritorio en cuatro filas. El toolbar operativo usa ahora la clase exclusiva `catalog-toolbar`; el demo conserva su jerarquía compacta. Una regresión Playwright a 1440 px exige que búsqueda/categoría y fechas desde/hasta compartan fila. Build, `typecheck:demo` y los nueve casos de `presentation-closure` pasan sobre la corrección; resta repetir el gate completo y las reviews sobre el nuevo SHA.

Gate completo local sobre `666f4c2`: `typecheck`, `typecheck:demo`, `typecheck:dct` y build PASS; Angular 401/401, dominio demo 80/80, Playwright demo 52/52 y ODR 4/4 PASS. El fallo de compilación observado dentro del sandbox no fue un fallo del producto: la misma suite Angular fuera de esa restricción completó 401/401. Resta review exact-head del commit documental final, CI hospedado, merge y verificación post-merge.
