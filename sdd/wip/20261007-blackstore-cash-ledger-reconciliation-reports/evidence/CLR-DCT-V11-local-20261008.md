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
