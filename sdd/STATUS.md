# Estado canónico del SDD — BlackStore

**Validado:** 2026-09-24  
**Madurez:** evidencia local en `master`. Companion live no aprobado.  
**Git:** `https://github.com/AgustinFalcon/blackstore` (privado). PR #2 mergeado (`395ca30`). PR #3 mergeado (`e10b525`). Sin tag ni publicación.

## Precedencia

1. Contrato HTTP canónico (StoreCore, no este repo):  
   `../StoreCore/sdd/wip/20260921-storecore-pos-integration-contract-v1/`  
   PIC-001..010 + L3 locales mergeados en StoreCore PR #19 (`18d18f7`). Módulo `DISABLED` fuera del test. Companion live NO-GO.
2. `sdd/wip/20260921-storecore-connector-adapter/` — ADP-001..010 + L3-001..003 locales en este `master` (fixture/localhost). PR #2 agrega el perfil opt-in `loopback` hacia `http://127.0.0.1:8080`; `application.yml` sigue fixture y fail-closed. Live/canary/rollout bloqueados. No `/sdd.finish`.
3. `sdd/wip/20260923-blackstore-frontend-ux-system-v1/` — sistema visual POS (docs + Stitch). BSUX-ANG en las 5 rutas existentes. PR #3 muestra catálogo y caja locales. El browser no llama a StoreCore. Stitch `projects/17616515208002773612`.
4. `sdd/wip/20260921-blackstore-pilot/` — piloto companion (puertos/fixtures hasta GO).
5. `sdd/wip/20260921-blackstore-pos-core/` — **superseded** (`SUPERSEDED.md`). ISSUE/REVERSAL no ejecutable.
6. `sdd/PROJECT.md`, `sdd/PATTERNS.md`, `sdd/TRACEABILITY.md`, `sdd/RELEASE.md`

## Gate actual

PR #1 (`87c95cf`), PR #2 (`395ca30`) y PR #3 (`e10b525`) mergeados tras dual Grok `APPROVED`. El perfil `loopback` es opt-in; un arranque normal de StoreCore sigue con el módulo `DISABLED`, así que los dos procesos no quedan conectados. GitHub no reportó checks; no es CI verde. Release disabled. Fiscal, companion live, MP-LIVE-05 y `/sdd.finish` NO-GO. WIP abierto.
