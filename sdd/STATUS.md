# Estado canónico del SDD — BlackStore

**Validado:** 2026-10-02
**Madurez:** evidencia local en `master`. Companion live no aprobado.
**Git:** `https://github.com/AgustinFalcon/blackstore` (privado). `origin/master` en `8fedbeb` (PR #17, issue [#16](https://github.com/AgustinFalcon/blackstore/issues/16)); PR #15 es el stamp intermedio `929d5e0`. Sin tag ni publicación.

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

PR #19 combina el corte issue #18 de vocabularios wire cerrados para las cinco rutas existentes y el workflow Verify de issue #20. La evidencia local registra build/TypeScript verdes; el runner Angular local no alcanzó assertions y el backend local terminó por un fallo del archivo binario de resultados de Gradle, no por una aserción. Hosted backend/frontend, dual Grok del HEAD final y merge siguen pendientes hasta quedar registrados. No hay cambio de contrato, ruta, backend, perfil, secreto ni activación. Un arranque normal de StoreCore sigue con el módulo `DISABLED`. Release disabled. Fiscal, companion live, MP-LIVE-05 y `/sdd.finish` NO-GO. WIP abierto.
