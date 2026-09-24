# Estado canónico del SDD — BlackStore

**Validado:** 2026-09-21  
**Madurez:** specs piloto. Contrato StoreCore **en WIP, no aprobado**.  
**Git:** `https://github.com/AgustinFalcon/blackstore` (privado). Rama UX: `feature/blackstore-frontend-ux-system-v1`. Sin tag ni publicación.

## Precedencia

1. Contrato HTTP canónico (StoreCore, no este repo):  
   `../StoreCore/sdd/wip/20260921-storecore-pos-integration-contract-v1/`  
   Estado: `ready_for_sol_review`, **no approved**. Conector real **bloqueado**.
2. `sdd/wip/20260921-storecore-connector-adapter/` — consumidor HTTP; `ready_for_sol_review`, **no approved**. ADP-001..010 + L3-001..003 locales done (fixture/localhost + dual Grok). Live/canary/rollout bloqueados. No `/sdd.finish`.
3. `sdd/wip/20260923-blackstore-frontend-ux-system-v1/` — sistema visual POS (docs + Stitch). BSUX-ANG en las 5 rutas existentes. Stitch `projects/17616515208002773612`.
4. `sdd/wip/20260921-blackstore-pilot/` — piloto companion (puertos/fixtures hasta GO).
5. `sdd/wip/20260921-blackstore-pos-core/` — **superseded** (`SUPERSEDED.md`). ISSUE/REVERSAL no ejecutable.
6. `sdd/PROJECT.md`, `sdd/PATTERNS.md`, `sdd/TRACEABILITY.md`, `sdd/RELEASE.md`

## Gate actual

Sol `20260923-sol-next-dev-go.md`: ADP-001..010 y L3 locales done bajo CONDITIONAL_GO fixture/localhost. Dual Grok r4 + L3 APPROVED. Release sigue disabled. Fiscal, companion live, MP-LIVE-05, merge y `/sdd.finish` NO-GO.
