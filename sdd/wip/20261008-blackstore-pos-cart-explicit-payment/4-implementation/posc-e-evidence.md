# Evidencia POSC-E — aceptación browser, seguridad y compatibilidad

Fecha: 2026-11-05. Rama: `codex/blackstore-posc-e`. Base integrada: `68ad3df60c39b53ba9cddc76cf234b5a06e26134`.

El SHA candidato, los veredictos Astra, el PR y los runs de GitHub se incorporarán después de crear el commit revisable. Este documento no los anticipa.

## Aceptación real combinada

- Playwright con Chromium, PostgreSQL 16 efímero, backend JAR real y frontend Angular: **4/4 PASS en 2,4 min** tras incorporar el restart A08.
- Recorrido responsive en 360, 768 y 1280 px: dos productos, reserva V3, reload Reserved, pago parcial CASH, reload, pago final CARD, commit explícito y reload Committed. El caso 1280 además reinicia el JAR propio con PID nuevo y vuelve a cargar el ticket multiline reservado sin repetir el POST.
- Se comprobaron requests, cardinalidad y correlación en base, comandos únicos, dos líneas, dos cobros, ledger, contratos de admisión y outbox. Completar el importe no produjo commit implícito.
- Pérdida de respuesta ReserveV3: una sola admisión; reload y recuperación sólo por GET; mismo body congelado y mismos hechos comerciales.
- Teclado, foco, labels, anuncios, ausencia de overflow y estados Editing/Reserved/AwaitingPayment/Completed cubiertos.
- Se generaron 12 capturas sanitizadas del área POS (cuatro estados por ancho). No contienen banner de staff, credenciales, SID, storage ni payloads; trace, video y captura automática están desactivados.
- Teardown de PostgreSQL, JAR y Angular: PASS en cada caso; los puertos quedaron libres.

## Seguridad browser

- DCT real: **3/3 PASS en 1,5 min**.
- SID/CSRF reales, ausencia de headers de autoridad, ownership opaco, roles no autorizados y payloads V3 inválidos dejan cero hechos comerciales.
- Tabs concurrentes no crean una segunda intención. Logout/cambio de actor cerca la finalización tardía; otro actor no puede recuperar; el mismo actor con SID nuevo recupera sólo por GET.
- Las negaciones pueden agregar auditoría de seguridad, pero no intents, receipts, outbox, tickets, líneas ni pagos.
- Límite explícito: la finalización tardía se prueba como pérdida de entrega después de una admisión real, no como una respuesta 2xx tardía visible en el documento original.

## Compatibilidad y artefactos

- Nueve combinaciones históricas V1 se sembraron directamente en IndexedDB con hashes fijos; runtime y `CommandHttp` hidrataron, consultaron GET v2 y sobrevivieron reload sin reescritura.
- La reapertura de un registro V2 máximo preservó body, hash, versión y ruta.
- Chrome focal: **30/30 PASS**. IndexedDB es real; el transporte HTTP de esta prueba focal es simulado y no se atribuye a PostgreSQL/JAR.
- CI conserva únicamente `results.json` y capturas sanitizadas bajo `dct-browser-results-current`; no guarda trazas, video ni sesión.

## Gates locales

- `npm run typecheck`: PASS.
- `npm run typecheck:dct`: PASS.
- `npm test -- --no-progress`: PASS, **379/379**.
- `npm run build`: PASS.
- `backend\\gradlew.bat test --no-daemon --max-workers=2`: PASS, **BUILD SUCCESSFUL en 7m 15s**.
- `git diff --check`: PASS después de la actualización documental.

## Corrección descubierta por la aceptación

La admisión CommitV2 puede aceptarse mientras la proyección durable sigue en `COMMIT_PENDING`. Una lectura única dejaba el journal en recuperación aunque el commit terminara correctamente. `AwaitTerminalSaleReadStep` modela `Pending`, `Complete` y `Unknown` como estados cerrados y hace polling GET-only, acotado e invalidable, tanto para commit como para release. Unknown, identidad/caja contradictoria o agotamiento permanecen fail-closed; nunca reenvía el POST. Las pruebas cubren Pending→Committed, Pending→Released, Unknown y cambio de generación.

## Límites honestos

- La fixture usa catálogo y datos controlados; no acredita catálogo, stock, Mercado Libre, fiscal ni Correo Argentino live.
- No acredita homologación externa ni publicación productiva.
- POSC-E sólo se marcará terminado tras revisión exacta del SHA, CI del PR y merge a `master`.
