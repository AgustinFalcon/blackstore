# DCT-T05/T06 — harness reproducible, aceptación local BLOCKED

2026-10-07. Base checkout 8fcff40c3252f79769bb48d631f6172d7901c301 (source DCT caab0a9 más reconciliación documental; master integrado documentado a9887a3); source local sin commit. Owner dct-browser en work/blackstore-master-audit. Reviews/CI de caab0a9 corresponden al corte mergeado: nueva review exact-head/CI de este incremento NOT_RUN.

Implementado plan Astra mínimo: fixture PG16 efímero, JAR real reiniciable, Angular/proxy y Chromium; cash-browser-restart/denials; tipos/mappers, package/lock, docs/testing/dct-browser-restart.md y job separado dct-browser. CashMutationHttpPostgresTest/StaffAuthHttpPostgresTest fijan blackstore.sales.worker.enabled=false para impedir actividad scheduled contra recursos efímeros sin cambiar producción.

## Verificación local

- npm lock-only y npm ci con ignore-scripts/cache dentro del workspace PASS tras permiso network. Intentos iniciales EACCES network/EPERM cache de usuario, retenidos como antecedentes.
- npm run typecheck:dct PASS tras corregir fallo inicial TS5107 usando moduleResolution Node16. npm run typecheck app/spec PASS.
- Playwright --list PASS: dos tests/dos archivos; sólo discovery.
- Playwright install chromium con PLAYWRIGHT_BROWSERS_PATH propio PASS, Chromium v1228/Chrome for Testing149.0.7827.55; instalación no acredita ejecución.
- npm run build FAIL entorno: esbuild no puede leer ancestro C:\ (Acceso denegado), falla resolución main.ts/zone.js/styles.css.
- npm run test:dct: dos FAIL setup clasificados BLOCKED, cero assertions. Docker run rechaza config.json de usuario y npipe docker_engine. Cero container/backend/frontend/browser iniciados. Primer intento imprimió password aleatoria efímera en error execFileSync: corregido a env/error saneado y repetido con mismo bloqueo sin credenciales. JSON final saneado en frontend/work/dct-browser-results/results.json; no publicar primer output.
- npm audit --omit=dev cero vulnerabilidades; audit completo siete high dev de Karma/braces/chokidar/source-map-js y efectos transitivos. No audit fix ni upgrades fuera de scope.
- `compileTestKotlin` PASS: incluye ambas clases HTTP PG modificadas. `test --tests com.blackstore.domain.* --tests com.blackstore.application.*` PASS: 54 tests/11 suites/cero failures/errors/skips, BUILD SUCCESSFUL1m37, Java17. GRADLE_USER_HOME y build dir aislados bajo work del repo; no cache compartida ni PG histórico. Primera llamada falló por quoting PowerShell de -P y ACL del build anterior; repetición con propiedad entre comillas/init script sin alterar source productivo. Artefactos work/dct-browser-backend/build/test-results/test/TEST-*.xml. Las dos clases HTTP PG no se ejecutaron: dependen de Docker (no se atribuye PASS runtime a compilación).
- git diff --check PASS.

## Recursos y gates

Docker negó creación antes de ID; no hay container propio que remover. No Java aplicación/Angular/browser iniciados; npm/Playwright/Gradle finalizaron. Puertos8081/4201 libres en preflight y comprobación final; no se tocaron PG18 históricos.

T05/T06 in_progress; WIP merged_partial_acceptance; Integration PARTIAL, PG16/browser/reinicio NOT_RUN a nivel gate. Job aún no despachado. Sin commit/push/PR/archivo/sdd.finish/tag/deploy, StoreCore live/fiscal ni homologación. Cierre requiere ejecución completa, teardown y review/CI sobre SHA exacto.
