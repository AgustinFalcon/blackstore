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
