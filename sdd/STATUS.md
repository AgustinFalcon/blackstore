# Estado canónico del SDD — BlackStore

**Validado:** 2026-10-07.
**Madurez:** core local fail-closed integrado; aceptación DCT aún parcial. Companion live y homologación pendientes.
**Git:** [BlackStore](https://github.com/AgustinFalcon/blackstore) privado. PR #26 MERGED, head `caab0a9`, merge master `a9887a3`. [CI PR 37634224683](https://github.com/AgustinFalcon/blackstore/actions/runs/37634224683) verde y [CI post-merge master 37634797108](https://github.com/AgustinFalcon/blackstore/actions/runs/37634797108) verde (frontend 34 s, backend 3 min 43 s). Sin tag ni publicación.

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

PR #24 incorporó identidad staff fail-closed (`4055b28`): sesión opaca persistida, cookie/CSRF, RBAC/ownership y /sesion; elimina autoridad desde X-Actor-Id/X-Role. El corte runtime comercial durable (`e6a47a3`) incorporó PostgreSQL autoritativo, recovery y consulta de ventas tras reinicio. La auditoría anterior de memoria/identidad describe el estado previo a esos merges; no es el estado actual. Las evidencias browser/PostgreSQL de esos WIPs no sustituyen aceptación DCT.

[PR #26](https://github.com/AgustinFalcon/blackstore/pull/26) incorporó apertura/cierre/egresos de caja con transacción, auditoría y lock durable compartidos. Titular ACTIVE y exactamente CASHIER; autoridad de actor separada, denegaciones con auditoría independiente. Dos revisiones independientes GPT-6.1 Sol (bugs y seguridad/arquitectura) APPROVED sobre el head exacto `caab0a9`; ambos CI hospedados verdes.

## Corte DCT integrado — 2026-10-07

WIP [durable-cash-transaction-boundary](wip/20261006-durable-cash-transaction-boundary/meta.md), base `e6a47a32b5f21b9f020fc1f9f23a73a19e5ed4a7`, branch `fix/durable-cash-transaction-boundary`: `merged_partial_acceptance`. Regresión local final 153 tests / 39 suites / 0 failures/errors/skips; enfocada 22 PASS, PostgreSQL 18 local/crash/HTTP y teardown PASS, seis clases Docker-only excluidas. Las regresiones de 143/148/149/151 y revisiones pendientes son antecedentes conservados en el WIP, sustituidos por este resultado sobre el source final.

Implementation PASS para el corte mergeado; Review APPROVED exact-head; Integration PARTIAL. PostgreSQL 16 y browser real con reinicio DCT siguen pendientes: no se atribuye PASS por CI verde, MockMvc ni aceptación browser de otros cortes. Ver evidence/DCT-T05-postgres.md, DCT-T06-browser-restart.md, DCT-review-p2-resolution.md y DCT-authorization-denial-resolution.md del WIP; fallos y bloqueos históricos preservados.

Libro de caja productivo, arqueo/diferencia, fórmulas y períodos SHIFT/DAY, E2E StoreCore+BlackStore con dos PostgreSQL, integración live, fiscal y MP-LIVE-05 permanecen pendientes. Homologation BLOCKED; Publication NOT_RUN. No /sdd.finish ni archivo del WIP.

## Estrategia de ramas para homologación

`master` recibe únicamente cortes core homologables y fail-closed, como tipos cerrados, UI local, documentación y CI. La integración operativa StoreCore↔BlackStore no entra en `master` durante esta homologación: se prepara en `release/1.0` sólo cuando facturación y Correo Argentino tengan su propia homologación completa. Hasta entonces, adapter live, credenciales, activación, canary y tráfico real permanecen fuera de alcance.
