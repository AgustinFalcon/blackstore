# Libro de caja, arqueo y reportes por período

## Corrección P2 Security T08-D — source vigente

2026-10-08: source `b0ab8c18ea678e829d2f7dc3ed6f6763335affa3` sobre `65407e2` exige HTTP real y envelope coherentes: HTTP503/body200 Found y code200/errorCode UNAVAILABLE quedan Unknown, journal bloqueado y recuperación GET-only. Typecheck/build PASS, Karma completo 227/227 PASS, audit productivo 0 y diff-check PASS. [Evidencia](evidence/CLR-T08-D-frontend-local-20261008.md). Sustituye únicamente source/validación local previos; PG16/browser/CI/reviews específicos siguen pendientes.

## Estado integrado local T08-D — vigente

2026-10-08: rama local `feat/cash-ledger-frontend-t08d`, merge no-ff `5417916` desde backend exacto `bb9e64b176939220c09267993cef9571824b60f2` con frontend exacto `3be9b4a327f1fc9ce8b642bff9a0c8567b9cb072`. Ambos historiales y addenda SDD preservados. Source frontend `b8024dcc0292897aa7a77f96e9ed9e25d63d54ea`: referencia original explícita y reversibilidad por captura no reembolsada; proyección cerrada GET-only, correlación intención/receipt/actor/caja/IDs/snapshot y refresh de contexto/lifecycle antes de Resolved. npm ci/typecheck/build PASS, Karma completo 223/223 PASS y audit productivo 0. C `implemented_pending_pg16_browser_ci_review`; T08 parcial/T09 planned. PG16/browser CLR/crash/restart/CI/reviews de este source pendientes; no homologación, activación ni publicación. Los registros debajo son antecedentes conservados y no sustituyen este estado.

Ver [evidencia local](evidence/CLR-T08-D-frontend-local-20261008.md).

## Antecedentes conservados — no son estado vigente

## Estado vigente del addendum de evidencia T08-D

2026-10-08: corte exclusivamente documental desde backend POS exacto `fc858de42a5e021aab2f47ab68794a2519ff2cb5`, branch `feat/cash-ledger-evidence-sdd`. [ADR-004](2-technical/adr/ADR-004-accounting-command-projection-evidence.md) proposed fija originalPaymentId nullable en lectura durable, validación refund→capture y GET de proyección de gasto por commandId con resultados cerrados/snapshot/autoridad actual. CLR-T08-D `prepared_pending_specific_review`; CLR-T08-D-BACKEND planned. Revisión y GO específicos pendientes, sin heredar aprobación ni acreditar implementación. C queda `blocked_on_pos_context_and_evidence` hasta B-POS y D-BACKEND integrados/revisados; T08 parcial y T09 planned. [Evidencia documental](evidence/CLR-T08-D-sdd-20261008.md). PG16/browser/CI/reviews de implementación siguen pendientes; no código productivo, migración, activación, homologación ni publicación.

### Antecedente T08-C integrado (conservado)

## Estado único vigente de C

`blocked_on_authoritative_read_models`; T08 parcial/no usable completo. T08-D, PG16/browser CLR/crash/CI pendientes. Source 7516b0e: focused 5/5/full 194/194/typecheck/build PASS, audit productivo 0; 3673658 189/5 FAIL se conserva. Hook preventivo focus/visible invalida inmediatamente y refetch guardado; nuevo source/tests NOT_RUN. [Evidencia vigente](evidence/CLR-T08-C-foreground-20261008.md).

## Antecedentes de C — no son estado actual

Validación externa 3673658: typecheck/build PASS (483.48 kB), tests FAIL 189/5 (focused integrado 0/5) por setup recursivo NG0101. Reparación del test mantiene flujo/assertions; nueva validación pendiente. [Detalle](evidence/CLR-T08-C-async-reserve-review2-20261008.md). C continúa bloqueado/T08 parcial.

Ronda 2 sobre 61ea8ad corrige Reserve asíncrona mediante espera autoritativa GET-only acotada, sin liberar journal/capturar durante Pending. [Evidencia](evidence/CLR-T08-C-async-reserve-review2-20261008.md); tests nuevos NOT_RUN, validación externa pendiente. C sigue blocked_on_authoritative_read_models/T08 parcial.

Antecedente posterior al review de 0a6bb36: C pasó a `blocked_on_authoritative_read_models`, T08 parcial. Fallback: tras refund no admite nuevas reversas; egreso no se resuelve sin GET autoritativo T08-D. Correcciones/tests se registraron entonces NOT_RUN; posteriormente se validó source7516. [Evidencia histórica](evidence/CLR-T08-C-review-corrections-20261008.md), sin heredar PASS entre sources.

Validación frontend externa exacta `82271775df12d79554aacbfc67030db3843f1445`: npm ci local/typecheck/build PASS y 161/161 unitarios PASS con launcher built-in ChromeHeadlessNoSandbox en unidad temporal T:, luego desmontada. Sin cambios source/CI; PG16/browser CLR/crash/CI/reviews y security triage de 7 high preexistentes siguen pendientes. [Detalle](evidence/CLR-T08-C-frontend-local-20261008.md).

Antecedente source 8227177 sobre `fc858de42a5e021aab2f47ab68794a2519ff2cb5`: entonces `implemented_pending_pg16_browser_ci_review`; [evidencia histórica](evidence/CLR-T08-C-frontend-local-20261008.md). No es estado actual: las brechas de read models mantienen C blocked_on_authoritative_read_models.

## Estado vigente del addendum POS

2026-10-08: CLR-T08-B-POS implementado por encargo explícito del coordinador sobre `d57cc7a9cb8edccf8a76cbca36eee12a661258ea`, branch `feat/cash-ledger-pos-context-backend`. Dominio/query/HTTP, V11, provisión administrativa documentada y validación transaccional Reserve/replay preparados. Estado `implemented_pending_pg16_ci_review`; ADR-003 conserva revisión específica pendiente y no hereda aprobación. [Evidencia del backend](evidence/CLR-T08-B-POS-backend-local-20261008.md) registra pruebas y límites. T08-C sigue `blocked_on_pos_context` hasta integración/reviews; T08 parcial, T09 planned. PG16/browser/CI/reviews no se declaran PASS por implementación.

Antecedente documental del 2026-10-07: base T08-B `39aa9d6dad199d88328e9c3799911e355a9cbddd`; [ADR-003](2-technical/adr/ADR-003-pos-execution-context.md) proposed, revisión específica pendiente. CLR-T08-B conserva implemented_pending_pg16_ci_review; CLR-T08-B-POS estaba planned. CLR-T08-C `blocked_on_pos_context`, T08 parcial y T09 planned. Ese corte sólo entregó documentación y no acreditó implementación ni homologación; el registro del 2026-10-08 arriba fija el estado vigente de B-POS.

- Feature: `blackstore-cash-ledger-reconciliation-reports`.
- Backlog: TODO-007. Fecha: 2026-10-07. Idioma: español.
- Estado: `active`. GO de diseño: aprobado 2026-10-07 por revisión funcional/arquitectura Astra, factibilidad/Bugbot Sol y seguridad Sol después de resolver todos los hallazgos P1/P2. La implementación queda autorizada sólo por los cortes secuenciales del plan; ningún runtime se activa por este GO.
- Ampliación CLR-T08-A done: [ADR-002](2-technical/adr/ADR-002-sale-v2-lifecycle-command-journal.md) accepted; GO DE DISEÑO explícito del usuario y APPROVE documental/Security sobre `0b4cddc7f26ec6965ad81443ec4cde74aadd2ce5`. Base `9e6e2cb0522bf2e001245a1593edeea2bb3136a6`; el amend sólo registra aprobación. T08 sigue parcial, T08-B/C y T09 planned. No aprueba implementación/runtime/activación/homologación.
- Base documental: master con DCT PR #28 integrado en `b8ce3b1`; el arnés PG16/browser/reinicio tiene aprobación exact-head y CI, mientras los escenarios contables CLR conservan gates propios.
- Fuentes: AGENTS.md; sdd/STATUS.md, PROJECT.md y PATTERNS.md; piloto ADR-004/ADR-006; WIPs staff identity, durable commercial runtime y durable cash transaction boundary; decisiones de planificación Astra entregadas al coordinador.
- Alcance: ledger append-only productivo, arqueo sin ajuste automático, reconocimiento comercial separado, SHIFT/DAY filtrados y completos explícitamente.
- Dependencias: SID persistente/RBAC, runtime comercial durable y locks DCT. La infraestructura base PG16/browser/reinicio quedó acreditada por PR #28 y CI `37657282222`; cada corte contable conserva sus escenarios CLR específicos como evidencia todavía no ejecutada. V8 disponible observada después de V1–V7; volver a verificar numeración antes de crear la migración.
- Gates actuales: Design original y ampliación T08-A PASS; Implementation PARTIAL_T08_V2_OPERATIONS_PENDING_SAGA_CONTRACT; Integration PG16/browser CLR NOT_RUN (evidencia PG18 anterior conservada); Review de implementación T07/T08 pendiente; Homologation BLOCKED; Publication NOT_RUN. [Tareas](3-tasks/tasks.json) conservan el detalle por corte, sin heredar PASS entre heads.
- Estrategia: PR de SDD/GO y cuatro cortes de implementación. Este corte sólo publica documentación: no contiene código, migraciones ejecutadas, activación runtime, deploy, publicación ni `/sdd.finish`.

StoreCore permanece fixture/disabled. Dos PostgreSQL, E2E live, fiscal, MP-LIVE-05, activación companion, facturación y Correo Argentino conservan gates externos independientes. La aceptación contable local no los habilita.
# Antecedente de implementación T08-C — 2026-10-08

Frontend source 8227177 sobre `fc858de42a5e021aab2f47ab68794a2519ff2cb5`: v2 saga/caja/pagos, contexto/lifecycle y journal implementados en ese corte histórico; estado entonces `implemented_pending_pg16_browser_ci_review`, sustituido por bloqueo de read models. No cierra gates B-POS/ADR-003/T08. [Evidencia histórica](evidence/CLR-T08-C-frontend-local-20261008.md).
