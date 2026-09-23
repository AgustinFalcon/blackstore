# Feature: BlackStore pilot

**Feature ID:** feat-20260921-blackstore-pilot · **Mode:** standard · **Project type:** production-pilot · **Language:** es

BlackStore es companion opcional 0..1 de una instalación StoreCore. Startup exige entitlement/subscription. Misma VM permitida con aislamiento estricto de proceso, DB, credenciales, identidad, secretos y audit. El único vínculo es API StoreCore versionada y una service identity BlackStore asociada a esa instalación.

**Status:** `ready_for_sol_review` (not approved). Contrato HTTP canónico vive en StoreCore.

- functional: `ready_for_sol_review`
- technical: `ready_for_sol_review`
- data_model: `ready_for_sol_review`
- api_draft: `ready_for_sol_review` (Markdown pointer only; YAML canónico en StoreCore)
- tasks: `ready_for_sol_review`
- implementation: `autonomous_core_implemented` (TASK-001..015 evidenced; StoreCore HTTP adapter still blocked)
