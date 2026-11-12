# Estado canónico del SDD — BlackStore

## Master vigente y cache durable fail-closed — 2026-11-12

`master 1f16522e78e7f7f04374ae1b58bcb2b92fa24e94` integra PR #66. El último
snapshot válido recibido por el modo opt-in `loopback` puede sobrevivir un
reinicio con procedencia cerrada `STORECORE_CACHE`; rechazo remoto, evidencia
inválida o cache vencido fallan cerrados. Fixture sigue predeterminado y no se
activaron StoreCore live, credenciales, canary, fiscal ni homologación.

La ronda final GPT-6.1 Sol Bug/Producto y Seguridad/Arquitectura aprobó sin
P0–P3; backend, frontend, frontend-browser y DCT alojados quedaron verdes. Este
bloque prevalece sobre referencias anteriores a PR #66 o que lo indiquen
pendiente.

## Master vigente y selección durable de operaciones — 2026-11-11

`master 367121871cc114ad214e236e499b2cb3683ca5b2` integra PR #63 y el
close-out documental PR #64. La selección durable de una operación de venta
quedó archivada en
`sdd/features/20261109-blackstore-sales-operation-selection/`: la UI no infiere
silenciosamente una operación ambigua y conserva autoridad cerrada del backend.

Verify post-merge de `master` `42728119683` terminó verde. Al iniciar esta
reconciliación no existían PRs abiertos; actualmente sólo está abierto el PR
documental #65 de esta misma reconciliación, sin PRs funcionales pendientes del
core local. Este bloque prevalece sobre las secciones históricas que todavía
describen POSC, CLR, DCT, ODR o paridad de superficies como pendientes.

La autoridad de catálogo, precio y stock continúa en StoreCore y el adapter
predeterminado permanece fixture/fail-closed. StoreCore live, fiscal/ARCA,
Correo Argentino, QR/proveedores, homologación, deploy y `release/1.0` no están
acreditados por este cierre.

## Master vigente y corte de paridad productiva — 2026-11-09

`master 40c2880` integra PR #53–#61. La [paridad de datos de superficies productivas](features/20261109-blackstore-productive-surface-data-parity/meta.md) se cerró en PR #61; Verify exact-head #109 (`42581477663`) dejó verdes frontend, frontend-browser, backend y DCT, y las reviews finales Bug/Security/UX-Producto aprobaron sin P0–P3. Este bloque prevalece sobre toda sección histórica posterior que aún describa POSC, ODR, presentación, caja/reportes o DCT como pendientes.

La demo comercial sigue siendo fixture en memoria. En el POS, identidad/caja/ventas/ledger son autoridad BlackStore con PostgreSQL; catálogo/precio/stock siguen bajo StoreCore y el adapter por defecto permanece fixture. StoreCore live, fiscal, QR/proveedores, homologación, deploy y `release/1.0` continúan bloqueados. Una procedencia histórica no persistida se muestra `Unknown`, nunca se infiere desde el catálogo actual.

## Histórico — corte de cierre frontend integrado — 2026-11-06

`master 7382616` integra PR #57 ODR y PR #56 POSC-E. El corte activo es [cierre de presentación frontend](wip/20261106-blackstore-frontend-presentation-closure/meta.md): demo comercial con mocks más profesionalización de lecturas POS existentes. Storefront comprador, proveedores reales, StoreCore live, fiscal, homologación y publicación siguen fuera de alcance/bloqueados.

## Master vigente y corte ODR — 2026-11-05

`master 822c431` integra PR #56 POSC-E. Las afirmaciones históricas posteriores que todavía describen POSC-B/T/U/E o PR #51 como pendientes no gobiernan el estado ejecutable actual. El siguiente corte activo es [rutas operativas de detalle](wip/20261105-blackstore-operational-detail-routes/meta.md), frontend-only y sin nuevas integraciones live.

## Candidato CLR-T09 local — 2026-11-05

Base `master c7ce4bd`, rama `codex/blackstore-clr-acceptance`: backend final
294/294 PASS y browser PostgreSQL 16/JAR/Angular 11/11 PASS en 5,6 min. Quedaron
cubiertos response-loss sin replay, cross-tab con mismo SID y RELEASE_PENDING
con reinicio/worker/cierre bloqueado y recuperación GET-only. La evidencia de
precio aceptada ahora atraviesa el reinicio del fixture sin fallback inventado.
Dos re-reviews Astra aprobaron `e3b342f` sin P0–P3. Falta comprobar el registro
documental final y cerrar CI del PR #51; homologación,
publicación y StoreCore live continúan bloqueados.

## Diseño POSC aprobado — 2026-10-20

[Carrito de mostrador y cobro explícito](wip/20261008-blackstore-pos-cart-explicit-payment/meta.md), origen PR #45 `79c612c78160135df0e9011d5bcd93533d14d4e8`: POSC-D aprobado por revisión coordinada técnica, seguridad y producto/UX, sin P0–P3 pendientes. Secuencia D/B/T/U/E: backend multiline versionado antes de tipos/journal y UX. Preserva fingerprints/journal v1; B/T/U/E, aceptación, CI y live permanecen pendientes en este head documental. Los estados históricos siguientes conservan sus fuentes y no sustituyen gates POSC.

### POSC-B local PASS, review pendiente — 2026-11-05

Sobre base `3a6a520555d499fceda835cf573f25921c922e5f`, el candidato POSC-B implementa reserva v3 multiline, AdmissionV2, metadata V13, lectura reconstruible y revalidación SID/terminal transaccional. Backend local: 311/311 PASS, cero omitidas; PG16 clean/upgrade incluido. Evidencia y límites en [POSC-B](wip/20261008-blackstore-pos-cart-explicit-payment/4-implementation/posc-b-evidence.md). No declara todavía review, CI, merge ni POSC-T/U/E.

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

Incremento de aceptación local 2026-10-07: harness Playwright/PG16/JAR y job separado dct-browser preparados; typechecks/discovery PASS, intento real BLOCKED antes de assertions por Docker pipe denegado. Review/CI sobre este nuevo source NOT_RUN. Ver evidence/DCT-browser-harness-20261007.md; no cambia el gate parcial ni hereda approvals del corte mergeado.

Libro de caja productivo, arqueo/diferencia, fórmulas y períodos SHIFT/DAY, E2E StoreCore+BlackStore con dos PostgreSQL, integración live, fiscal y MP-LIVE-05 permanecen pendientes. Homologation BLOCKED; Publication NOT_RUN. No /sdd.finish ni archivo del WIP.

## Estrategia de ramas para homologación

`master` recibe únicamente cortes core homologables y fail-closed, como tipos cerrados, UI local, documentación y CI. La integración operativa StoreCore↔BlackStore no entra en `master` durante esta homologación: se prepara en `release/1.0` sólo cuando facturación y Correo Argentino tengan su propia homologación completa. Hasta entonces, adapter live, credenciales, activación, canary y tráfico real permanecen fuera de alcance.
