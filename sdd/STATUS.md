# Estado canónico del SDD — BlackStore

**Validado:** 2026-09-21  
**Madurez:** specs piloto. Contrato StoreCore **en WIP, no aprobado**.  
**Git:** `https://github.com/AgustinFalcon/blackstore` (privado). Rama UX: `feature/blackstore-frontend-ux-system-v1`. Sin tag ni publicación.

## Precedencia

1. Contrato HTTP canónico (StoreCore, no este repo):  
   `../StoreCore/sdd/wip/20260921-storecore-pos-integration-contract-v1/`  
   Estado: `ready_for_sol_review`, **no approved**. Conector real **bloqueado**.
2. `sdd/wip/20260921-storecore-connector-adapter/` — plan formal consumidor HTTP; `ready_for_sol_review`, **no approved**, bloqueado por evidencia StoreCore + GO Sol propio.
3. `sdd/wip/20260923-blackstore-frontend-ux-system-v1/` — sistema visual POS (docs + Stitch). Angular volcado bloqueado. Stitch `projects/17616515208002773612`.
4. `sdd/wip/20260921-blackstore-pilot/` — piloto companion (puertos/fixtures hasta GO).
5. `sdd/wip/20260921-blackstore-pos-core/` — **superseded** (`SUPERSEDED.md`). ISSUE/REVERSAL no ejecutable.
6. `sdd/PROJECT.md`, `sdd/PATTERNS.md`, `sdd/TRACEABILITY.md`, `sdd/RELEASE.md`

## Gate actual

BlackStore implementa el core autónomo del piloto (Sol GO §B). Las 14 tareas de `20260921-blackstore-pilot` tienen evidencia. El perfil `local` persiste caja, cierre auditado, reserva, snapshot de ticket, pago dividido, reversa, gasto, commit y release del simulador en el PostgreSQL propio del proceso (puerto 5433). Los reportes de turno y día salen de esos registros. Commit en producción sigue exigiendo autorización fiscal externa; este perfil es TEST. Los tests siguen en memoria. El cliente HTTP real sigue bloqueado hasta evidencia aceptada StoreCore `TASK-PIC-001..008`, SHA-256/version `1.0.0-draft` bloqueado y GO explícito de Sol para `20260921-storecore-connector-adapter`.
