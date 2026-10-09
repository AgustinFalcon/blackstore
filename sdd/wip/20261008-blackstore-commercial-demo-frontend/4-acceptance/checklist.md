# Evidencia requerida

Estado inicial de E01..E10: NOT_RUN. Registrar comando/browser, fecha, SHA, resultado y artefacto. Un build verde no prueba botones ni diseño.

- E01 — Aislamiento: recorrer todas las rutas demo con API inaccesible; bloquear/registrar requests de negocio y comprobar cero llamadas. Sesión/cookies/colas reales no cambian; acceso directo a rutas operativas sigue guardado.
- E02 — Venta integral: dos productos, cantidades distintas, cliente, descuento; elegir cada medio en pruebas separadas; comprobar total/recibo/caja/stock/ventas/reportes y repetición de command ID sin duplicar. Doble clic rápido y reload con aviso documentado.
- E03 — Restricciones: caja cerrada, stock agotado, cantidad inválida, precio/stock cambiado, Unknown, pago insuficiente y error recuperable. No venta parcial ni stock negativo.
- E04 — Caja/reversa: apertura, efectivo con vuelto, egreso, arqueo con diferencia, cierre y nueva apertura. Reversa una sola vez; original preservado; todos los totales consistentes.
- E05 — CRUD simulado: producto/cliente válido e inválido, SKU duplicado, cancelación, activar/desactivar y ajuste inventario con motivo. Snapshot histórico inmutable.
- E06 — Lecturas/exportación: filtros, paginación, vacío, detalle inexistente, CSV filtrado y escapado, impresión/descarga de recibo visibles y coherentes, reportes sin costo muestran margen desconocido.
- E07 — Diseño: captura de cada ruta y principales overlays a desktop/móvil, auditoría independiente de diseño con hallazgos resueltos. Tablet y 200% zoom sin acciones perdidas ni texto cortado.
- E08 — Accesibilidad: navegación completa por teclado, foco modal y retorno, labels/error, landmarks, contraste, targets, anuncio de resultados. Chequeo automatizado complementa revisión manual.
- E09 — Regresión: rutas reales, guards y recovery no cambian; suites existentes y build frontend PASS. Pruebas de dominio cubren invariantes reales, no sólo literales de implementación.
- E10 — Inventario: cada CTA renderizado está reconciliado con inventario y evidencia; escenarios/reset son repetibles. No dead buttons, links ficticios, TODO visibles ni éxitos simulados que no muten el estado.

Salida permitida: «Frontend demo cubierto para el inventario verificado», con métricas de rutas/CTA/escenarios y límites. Prohibido declarar 100% backend, integración StoreCore/ML, homologación fiscal o seguridad productiva por aprobar este corte.
