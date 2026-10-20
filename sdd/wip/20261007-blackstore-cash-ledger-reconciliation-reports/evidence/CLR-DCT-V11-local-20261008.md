# DCT browser v2 / V11 — reparación del harness

Base exacta `9f4d29ef98c2fb6101f385e83dcf985d0827529f` (T08-D frontend), rama `feat/cash-ledger-dct-v11`, worktree `work/blackstore-clr-dct-v11`. Sólo harness/tests y documentación: no cambios de dominio, frontend productivo, migraciones ni workflow.

El harness anterior conservaba POST v1 y max Flyway=7. La UI actual exige contexto POS y runtime activo antes de habilitar Abrir sesión. La fixture nueva usa exclusivamente su container PG16 efímero; aplica V1→V11 mediante JAR, detiene bootstrap, provisiona terminal/binding/conector explícito y ACTIVE en transacción administrativa, luego inicia JAR configurado y Angular. No seed inferido, identidad browser, mock HTTP, skip ni aumento de timeout. Reinicio conserva la misma configuración/provisión y sólo termina el PID propio.

Browser preparado: comandos v2 reales correlacionados con receipts/postings/settlement/auditoría/arqueo, journal IndexedDB y reload/reinicio sin POST automático. Denegaciones v2 y escrituras legacy/Paused conservan todos los hechos. La UI stale actual consulta referencias y se bloquea antes del POST; se conserva un caso HTTP real CLOSED separado. Gates anteriores no se heredan.

Validación local:

- `npm run typecheck:dct`: PASS sobre source final. Dependencies reutilizadas mediante junction local ignorada hacia frontend T08-D; primer intento con dependencies incompletas falló por módulos ausentes, corregido sin modificar lockfile.
- `npm run test:dct -- --list`: PASS, dos tests descubiertos.
- `node node_modules/typescript/bin/tsc -p tsconfig.dct.json --noEmit false --outDir work/dct-compiled`: PASS; compilación de harness real.
- Adquisición directa de `DctDatabase.start()` compilada, con `finally stop()`: BLOCKED antes de adquirir PG16. Docker devuelve acceso denegado a `npipe:////./pipe/docker_engine` y lectura config.json. No assertions PG16/browser ejecutadas: NOT_RUN.
- `git diff --check`: PASS. BootJar/backend suites: NOT_RUN (no backend productivo cambiado). CI/reviews exact-head: PENDING. El job existente construye JAR exacto, npm ci, Chromium y PG16; la reparación queda reproducible allí.

Teardown: no container adquirido, ningún Java/Angular iniciado; `stop()` intentó recuperación por nombre único sin tocar recursos externos. No push/PR/deploy ni activación productiva. Aceptación CLR/DCT permanece pendiente hasta PG16/browser completo; sin homologación ni publicación.

## Review P3 — refuerzo sobre `94316853a7278a3664c7693afd4328f21eacc9a7`

Los tres casos opacos (caja ajena abierta, inexistente, ajena cerrada) ahora verifican ausencia/null de command/cash/sale/payment/expense/settlement/actor/terminal/device/client IDs, committedAt/payloadHash/closeSnapshot y ledgerEventIds vacío. Se compara la respuesta completa (status, cache-control, envelope/data) excluyendo únicamente traceId. No se exige data=null: un recibo tipado neutro sigue permitido, cualquier evidencia sensible falla.

Fixture/spec parametrizan SQL de activación/pausa con `AccountingLifecycleState.Active.wire` y `.Paused.wire`, reutilizando el tipo cerrado de dominio sin importar infrastructure/UI.

Validación sobre el refuerzo: `npm run typecheck:dct`, `npm run test:dct -- --list` (2 tests), compilación tsc del harness y `git diff --check` PASS. PG16/browser continúa NOT_RUN por el bloqueo Docker ya registrado; no se repitió adquisición ni se iniciaron servicios. Nuevo commit separado, sin amend/push/PR; worktree entregado limpio.

## CI PR #45 — lectura determinista del receipt durable

El CI remoto reportó `Network.getResponseBody: No data found` en `cash-session-page.ts:20`, al pedir `response.json()` después del auto-wait de click y los refresh de Angular. Falló el mecanismo CDP de lectura del body, sin evidencia de rechazo del comando de negocio.

El helper captura status y commandId del POST al evento response; nunca lee su body mediante CDP. Exige POST 200 y commandId UUID, luego Chromium realiza GET read-only `/api/v2/accounting/commands/{commandId}` con su SID real y consume JSON dentro de `page.evaluate`. Exige GET 200/no-store, receipt Committed/failure None y commandId exacto; apertura/cierre correlacionan cashSessionId con UI, gasto exige expenseId/settlementId y cierre snapshot. Las assertions PostgreSQL de receipts/ledger/settlements/journal conservan su correlación y hechos íntegros. GET no ejecuta, reenvía ni sustituye un POST; no se añade polling/fallback/retry ni se altera timeout/contrato.

Revisados los helpers análogos: apertura/egreso/cierre usaban response.json tardío y ahora comparten la misma lectura durable; login/reload sólo observan status y no dependen de body CDP. Denials ya consumía JSON dentro de Chromium y permanece intacto. No se añadió un test HTTP simulado: los dos escenarios reales existentes exigen el receipt autoritativo por comando; una doble falsa que devolviera receipt no acreditaría la retención de Chrome.

Validación final local: typecheck:dct, discovery 2 tests, compilación tsc del harness y diff-check PASS. Browser/PG16 local NOT_RUN por Docker ya bloqueado; nuevo CI remoto y revisión exact-head pendientes. Sin servicios adquiridos ni push/PR; commit nuevo separado.
