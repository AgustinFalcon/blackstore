# Estado canónico del SDD — BlackStore

**Validado:** 2026-10-06
**Madurez:** simulador local parcial; identidad, recovery durable, caja contable y E2E real pendientes. Companion live no aprobado.
**Git:** `https://github.com/AgustinFalcon/blackstore` (privado). `origin/master` en `b9211764f525d723d020c780d4eb62564ffaadf6` (PR #23). CI post-merge `37398297188` verde. Sin tag ni publicación.

## Precedencia

1. Contrato HTTP canónico (StoreCore, no este repo):  
   `../StoreCore/sdd/wip/20260921-storecore-pos-integration-contract-v1/`  
   PIC-001..010 + L3 locales mergeados en StoreCore PR #19 (`18d18f7`). Módulo `DISABLED` fuera del test. Companion live NO-GO.
2. `sdd/wip/20260921-storecore-connector-adapter/` — ADP-001..010 + L3-001..003 locales en este `master` (fixture/localhost). PR #2 agrega el perfil opt-in `loopback` hacia `http://127.0.0.1:8080`. PR #5 ata el reserve a la variante y la versión de precio del catálogo vigente. `application.yml` sigue fixture y fail-closed. Live/canary/rollout bloqueados. No `/sdd.finish`.
3. `sdd/wip/20260923-blackstore-frontend-ux-system-v1/` — sistema visual POS (docs + Stitch). BSUX-ANG en las 5 rutas existentes. PR #3 muestra catálogo y caja locales. El browser no llama a StoreCore. Stitch `projects/17616515208002773612`.
4. `sdd/wip/20260921-blackstore-pilot/` — piloto companion (puertos/fixtures hasta GO).
5. `sdd/wip/20260921-blackstore-pos-core/` — **superseded** (`SUPERSEDED.md`). ISSUE/REVERSAL no ejecutable.
6. `sdd/PROJECT.md`, `sdd/PATTERNS.md`, `sdd/TRACEABILITY.md`, `sdd/RELEASE.md`

## Gate actual

En `origin/master`, PRs #1–#8, #11, #13, #15 y #17 mergearon con sus pares Grok `APPROVED`. PR #9 mergeó a `integration/blackstore` (`531731f`) y no está en este `master`; no hay par Grok para #9. #10, #12 y #16 son issues. El perfil `loopback` es opt-in. Issue [#10](https://github.com/AgustinFalcon/blackstore/issues/10): el dispatch HTTP local usa coroutines (`delay`), no `Thread.sleep`. Issue [#12](https://github.com/AgustinFalcon/blackstore/issues/12): el hilo IO llega por `DispatcherProvider` (como GoodLife Android); no se inyecta en dominio ni en workers `@Scheduled`. Issue [#16](https://github.com/AgustinFalcon/blackstore/issues/16): reutilizar el mismo provider si aparece otro HTTP bloqueante; sin código ahora. No es un dispatcher remoto de Mercado Libre.

PR #19 cerró los vocabularios wire de las cinco rutas existentes. PR #22 agregó fixtures de catálogo seguros. PR #23 incorporó política local fail-closed de ticket/pago, dinero exacto, correlación y coordinación en una instancia; sus reviews de bugs, seguridad y SDD aprobaron el HEAD exacto y el CI post-merge `37398297188` quedó verde.

Ese CI no acredita producto completo. La auditoría funcional posterior confirmó que el runtime normal sigue con memoria/fixture, la saga consultable no se rehidrata tras reinicio, reportes no separan correctamente turno/día y no existe E2E browser→backends→PostgreSQL. El bloqueo inmediato es identidad: varios endpoints confían en `X-Actor-Id`/`X-Role` y otros no exigen actor. El WIP activo `20261006-blackstore-staff-identity` reemplaza esa frontera; no habilita companion live, fiscal, MP-LIVE-05 ni `/sdd.finish`.

## Siguiente corte de caja — 2026-10-06

Actualización 2026-10-07: dos P2 corregidos (Jackson para audit JSON y 404 de memoria independiente del orden de blockers). Suite enfocada 17 PASS; amplia nativa compatible final 148 PASS, 0 failures/errors/skips; run suspendido 147 PASS/1 FAIL retenido con disposición de teardown y repetición sin cambios de código. Evidencia `DCT-review-p2-resolution.md`. PG propios apagados y listener5441 ausente. Nueva review independiente pendiente; los gates globales parciales/bloqueados permanecen.

Resultado actualizado del corte local: `implementation_partial`. Backend 143 tests PASS, 0 failures/errors/skips, PostgreSQL18 real con seis crash y roles/locks/HTTP; seis clases Docker anteriores excluidas por acceso al pipe denegado. UI typecheck PASS; UI build/tests y browser/reinicio BLOCKED por entorno. La creación IAB oculta no retornó y fue abortada antes de assertions; no backend/frontend iniciado. PostgreSQL DCT PID70556 detenido, puerto5441 y procesos hijos ausentes. Evidencia en `evidence/DCT-T05-postgres.md` y `evidence/DCT-T06-browser-restart.md`. PG16, CI hospedado y reviews finales pendientes; sin commit/push/publicación.

WIP [durable-cash-transaction-boundary](wip/20261006-durable-cash-transaction-boundary/meta.md), base `e6a47a32b5f21b9f020fc1f9f23a73a19e5ed4a7`, branch `fix/durable-cash-transaction-boundary`: `implementation_in_progress`. T01 aprobada por GPT-6.1 Sol medium después de R1, sin P0–P3; T02–T06 en validación. Código local implementa comandos de apertura/cierre/egreso con auditoría en la misma conexión/transacción, lock común y revalidación durable. Primera validación enfocada: 17 tests PASS, incluido PostgreSQL 18 local, seis crash de proceso y matriz SID/HTTP; regresión final en progreso. Browser, PostgreSQL 16/CI y revisiones finales aún pendientes. La base anterior y sus CI son historia, no evidencia del diff actual. Libro/arqueo/reportes y live/fiscal permanecen pendientes; no `/sdd.finish` ni publicación.

## Estrategia de ramas para homologación

`master` recibe únicamente cortes core homologables y fail-closed, como tipos cerrados, UI local, documentación y CI. La integración operativa StoreCore↔BlackStore no entra en `master` durante esta homologación: se prepara en `release/1.0` sólo cuando facturación y Correo Argentino tengan su propia homologación completa. Hasta entonces, adapter live, credenciales, activación, canary y tráfico real permanecen fuera de alcance.
