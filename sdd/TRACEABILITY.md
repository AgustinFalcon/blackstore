# RTM — `blackstore-pilot`

CLR-T08-C validación local externa source `82271775df12d79554aacbfc67030db3843f1445`: lockfile/npm ci, typecheck y build PASS; unitarios 161/161 PASS en ChromeHeadlessNoSandbox built-in desde T: temporal desmontada, sin cambios source/CI. [Evidencia](wip/20261007-blackstore-cash-ledger-reconciliation-reports/evidence/CLR-T08-C-frontend-local-20261008.md). T08 parcial; PG16/browser CLR/crash/CI/reviews pendientes, 7 high preexistentes pendientes security triage.

CLR-T08-C 2026-10-08: source v2/journal/contexto/lifecycle implementado sobre `fc858de42a5e021aab2f47ab68794a2519ff2cb5`, con validación local PASS sobre source 82271775df12d79554aacbfc67030db3843f1445 y aceptación CLR/reviews exact-head pendientes. [Evidencia](wip/20261007-blackstore-cash-ledger-reconciliation-reports/evidence/CLR-T08-C-frontend-local-20261008.md). Sustituye el bloqueo de implementación C del corte B-POS siguiente; no sustituye los gates pendientes ni cierra T08.

## CLR — contexto POS verificado (addendum 2026-10-07)

2026-10-08: CLR-T08-B-POS implementado sobre `d57cc7a9cb8edccf8a76cbca36eee12a661258ea`: `domain/pos/PosExecutionContext`, puerto PosContextQuery, PosContextValidationStep, JdbcPosContextReader/Query, PosContextController, V11 y POS-CONTEXT-PROVISIONING. PosContextTest/ControllerTest/UnavailableTest y SaleAdmissionPostgresTest trazan traducción/Unknown, autoridad/no-store, indisponibilidad, replay/opacidad, ausencia/concordancia, clean/upgrade/inmutabilidad/grants y carreras. [Evidencia local](wip/20261007-blackstore-cash-ledger-reconciliation-reports/evidence/CLR-T08-B-POS-backend-local-20261008.md). Implementación no equivale a aceptación: PG16/browser/CI/reviews pendientes; T08-C sigue bloqueado y T08 parcial.

CLR-004/009 → CLR-T08-B-POS: binding V11, consulta de contexto, validación de nueva Reserve, replay previo/autoridad, historia conservada. CLR-001/009 → B-POS/C: PosExecutionContext cerrado, mapper único y bloqueo Unknown. CLR-004/010 → C/T09: journal origen/instalación/dispositivo lógico, SID nuevo/actor/tab/contexto, browser/PG16/carreras sin duplicación automática. [ADR-003](wip/20261007-blackstore-cash-ledger-reconciliation-reports/2-technical/adr/ADR-003-pos-execution-context.md) y [evidencia documental](wip/20261007-blackstore-cash-ledger-reconciliation-reports/evidence/CLR-T08-B-POS-sdd-20261007.md). T08-C blocked_on_pos_context; T08 parcial; implementación/review/PG16/browser propios pendientes.

## CLR — ampliación contractual T08-A, 2026-10-07

Base exacta `9e6e2cb0522bf2e001245a1593edeea2bb3136a6`; documentación solamente. [ADR-002](wip/20261007-blackstore-cash-ledger-reconciliation-reports/2-technical/adr/ADR-002-sale-v2-lifecycle-command-journal.md), [plan](wip/20261007-blackstore-cash-ledger-reconciliation-reports/3-tasks/plan.md) y [tareas](wip/20261007-blackstore-cash-ledger-reconciliation-reports/3-tasks/tasks.json) proponen el cierre de residuales; no sustituyen evidencia de ejecución.

- CLR-001/004/009 → T08-A diseño; T08-B tipos/contratos saga v2, recibos, autoridad/CSRF/ownership, lifecycle GET y V10 propuesta; T08-C mapper/política/journal; T09 prueba real y reviews.
- CLR-003/005/006 → T08-B Accepted distinto de COMMITTED, outbox/evidencia/reconocimiento/terminalidad, crash/cierre/pausa y sin backfill; T08-C refresh autoritativo; T09 reserva→pago→commit y reversión→release antes de cierre.
- CLR-002/008 → T08-B rechazo explícito feeAmount en captura y conservación FeeRecord OWNER interno; T08-C elimina entrada de comisión no soportada, conserva lectura histórica; T09 regresión de medios/fórmulas sin doble FEE.
- CLR-007/008 → T07/T08 lecturas existentes conservadas; T08-C no mezcla contexto/actor; T09 SHIFT/DAY/completitud, sin atribuir PASS por este documento.
- CLR-010 → T08-C journal antes de POST, reload/SID/tab/respuesta tardía/NotFound; T09 browser CLR separado del arnés DCT legacy, PG16, reinicio, teardown y rechazo writers v1 en Active/Paused.

T08-A done: GO DE DISEÑO explícito del usuario y APPROVE documental/Security sobre `0b4cddc7f26ec6965ad81443ec4cde74aadd2ce5`, sin aprobación de implementación/runtime. T08-B/C planned, T08 parcial, T09 planned. Tests nuevos de implementación y aceptación NOT_RUN; reviews runtime pendientes, homologación BLOCKED/publicación NOT_RUN. [Evidencia documental](wip/20261007-blackstore-cash-ledger-reconciliation-reports/evidence/CLR-T08-A-contracts-20261007.md). Sin activación, archivo ni `/sdd.finish`.

## Estado vigente — 2026-10-07

Incremento local de aceptación (base checkout8fcff40): DCT-001..007 → frontend/e2e/dct/cash-browser-restart.spec.ts y denials.spec.ts, fixture PG16/JAR/Chromium y job dct-browser. Domain/application54 tests/11 suites y compileTestKotlin PASS; typechecks/discovery PASS. Browser/PG16 BLOCKED antes de assertions por Docker pipe; review/CI nuevos NOT_RUN, no heredan caab0a9. Evidencia DCT-browser-harness-20261007.md del WIP; no cierra T05/T06.

El corte DCT está `merged_partial_acceptance`: PR #26 MERGED, head `caab0a9`,
merge master `a9887a3`; CI PR `37634224683` y post-merge master `37634797108`
verdes. Dos revisiones independientes GPT-6.1 Sol sobre el head exacto aprobaron
bugs y seguridad/arquitectura sin P0–P3. T01–T04 están done; T05 y T06 siguen
in_progress. La validación local final conserva 153 tests / 39 suites / cero
failures, errors o skips sobre PostgreSQL 18, con seis clases Docker-only
excluidas. PostgreSQL 16 y browser real con reinicio DCT permanecen pendientes;
Integration es PARTIAL, Homologation BLOCKED y Publication NOT_RUN. Libro,
arqueo, fórmulas SHIFT/DAY e integración live quedan fuera de este corte.

## Antecedentes de implementación y correcciones

DCT-004 P2 Security titular→Jdbc LoadCashAuthority.eligibleOwner/memory owner: ACTIVE+CASHIER exacto, no permisos actor como elegibilidad. InMemoryCashMutationCommandsTest y CashMutationHttpPostgresTest matrix supervisor/owner/inactivo/missing → 404 y una auditoría independiente, actor supervisory→cashier activo permitido. Enfocada22/amplia FINAL153 PASS/39 suites/cero failures/errors/skips, teardown75632/5441/children/launcher PASS; seis exclusiones Docker, nueva review/PG16/browser/CI pendientes.

DCT validación source final post-residual: amplia nativa151 PASS/39 suites/cero failures/errors/skips y enfocada20 PASS, mismas seis exclusiones Docker. Teardown5020/5441/children/launcher PASS; evidencia DCT-authorization-denial-resolution.md. Reemplaza amplia149 antecedente; no satisface PG16/browser/CI.

DCT-004 residual → LoadCashAuthority.actor/eligibleOwner y equivalentes memory con Authorization; pruebas actor inactivo/owner inelegible en InMemoryCashMutationCommandsTest y CashMutationHttpPostgresTest. Enfocada final20 PASS, teardown50428/5441 PASS; amplia149 antecedente previo, revisión nueva pendiente.

Corrección DCT exact-head 2026-10-07: DCT-004/005 → `CashRejectionSource` + adapters + `AuthorizeStaffAction.recordCashDenial` en application cash/counter. Regresiones `CashDenialAuditTest` y `CashMutationHttpPostgresTest`: ownership404, override400, permiso application403, auditoría independiente exacta y cero denegaciones para validación común/conflicto; asserts PG/memory de procedencia preservados. Enfocada18/amplia nativa149 PASS y teardown PASS, evidencia `DCT-authorization-denial-resolution.md`. Este bloque es antecedente anterior al source final, a las reviews y al CI vigentes.

**Estado histórico de esta sección:** `ready_for_sol_review`; supersedido por el
estado vigente anterior. El RTM `blackstore-pos-core-v1.0.0` es histórico.

| Capacidad | Tarea | Gate |
|---|---|---|
| skeleton hexagonal + POS Angular | TASK-001 | domain sin Spring |
| Flyway modelo BlackStore | TASK-002 | sin `store_id`, sin tablas StoreCore |
| operadores USER y roles | TASK-003 | CASHIER/SUPERVISOR/OWNER/AUDITOR; no CUSTOMER StoreCore |
| ticket + saga reserve/commit | TASK-004 | AC-4 cuádruple + outbox |
| Contrato StoreCore v1 | externo `storecore-pos-integration-contract-v1` | OpenAPI canónico `/blackstore-integration/v1`; WIP no approved |
| Adaptador consumidor StoreCore | `20260921-storecore-connector-adapter` | `ready_for_sol_review` no approved; ADP-001..010 + L3 locales done; live NO-GO; no `/sdd.finish` |
| POS UX docs/Stitch | `20260923-blackstore-frontend-ux-system-v1` | docs + BSUX-ANG en 5 rutas existentes; no pixel-complete; POS-06/07/08 sin ruta nueva |
| pos-core ISSUE | `20260921-blackstore-pos-core` | **superseded** — evidencia histórica |
| caja y arqueo | TASK-007 | AC-5 |
| tests de contrato e invariantes | TASK-010 | idempotencia, inbox PENDING, reconcile read-only |
| loopback HTTP en coroutines | issue [#10](https://github.com/AgustinFalcon/blackstore/issues/10) | `delay` + `Dispatchers.IO`, no `Thread.sleep` |
| `DispatcherProvider` inyectado | issue [#12](https://github.com/AgustinFalcon/blackstore/issues/12) | IO por constructor/Spring; no dominio ni `@Scheduled` |
| Reusar provider en HTTP nuevo | issue [#16](https://github.com/AgustinFalcon/blackstore/issues/16) | futuro; no código ahora; live/fiscal/ML NO-GO |
| vocabularios wire POS cerrados | issue [#18](https://github.com/AgustinFalcon/blackstore/issues/18) / PR #19 | `pos-types.ts`, `PosWireMapper`, tipos `Unknown` fail-closed y regresiones de las cinco rutas existentes; sin ruta/backend/contrato nuevo |
| Verify hospedado | issue [#20](https://github.com/AgustinFalcon/blackstore/issues/20) / PR #19; DCT PR #26 | `.github/workflows/verify.yml`: backend tests + frontend tests/build, permisos read-only, actions por SHA; DCT CI PR `37634224683` y post-merge `37634797108` verdes |

## Adapter dependency map

## Frontera durable de caja

Corrección P2 2026-10-07: auditoría JSON→`JdbcBlackStoreWriter`/test PG con controles; paridad visibilidad→`InMemoryCashMutationCommandsTest` + PG dual blocker en ambos órdenes. Enfocada 17 PASS, amplia nativa compatible 148 PASS; run fallido retenido y teardown documentados en `evidence/DCT-review-p2-resolution.md`. Nueva revisión independiente pendiente; no cierra browser/PG16/CI ni Integration global.

Antecedente local `implementation_partial`: T02–T05 backend 143 tests PASS, incluida matriz HTTP/PG y seis crash; PG16/suite Docker pendiente. T06 browser/reinicio BLOCKED antes de assertions (IAB visible no soportado en subagente; creación oculta abortada por usuario tras bloqueo). UI typecheck PASS, build/tests UI BLOCKED. PostgreSQL propio detenido y listener/hijos ausentes. Artefactos: `wip/20261006-durable-cash-transaction-boundary/evidence/DCT-T05-postgres.md` y `evidence/DCT-T06-browser-restart.md`. Este antecedente fue superado por reviews y CI, pero su bloqueo PG16/browser continúa vigente.

Corrección Sol R1: DCT-001/002/003/004/007 también trazan a T02 (visibilidad SID separada de elegibilidad y resultados cerrados) y T06 (handler/controllers/Angular y pruebas HTTP/UI 404 frente a 409). GPT-6.1 Sol medium aprobó la corrección sin P0–P3; evidencia en `evidence/DCT-T01-sdd-review.md`. La condición SQL `OPEN` y rowcount se conservan. Los dos índices existentes de apertura (terminal V1/cajero V4) comparten traducción SQLSTATE/constraint y filtro de visibilidad aprobado explícitamente durante implementación.

El corte [durable-cash-transaction-boundary](wip/20261006-durable-cash-transaction-boundary/meta.md) está `merged_partial_acceptance`. [Plan trazable](wip/20261006-durable-cash-transaction-boundary/3-tasks/plan.md): DCT-001 apertura → T03/T05; DCT-002 cierre → T03/T05; DCT-003 egreso serializado → T04/T05; DCT-004 autoridad y DCT-005 dinero/tipos → T02/T03/T04/T05/T06; DCT-006 crash/reinicio → T05/T06; DCT-007 evidencia real → T06. T01–T04 done; T05/T06 in_progress. `CashMutationPolicyTest` prueba reglas; `DurableCashPostgresTest` prueba hechos, roles, rollback, lock, carreras y seis crash; `CashMutationHttpPostgresTest` prueba SID/HTTP y cardinalidad en PG real. Review exact-head y Hosted PASS. Integration completa todavía requiere browser y PG16. Ledger/arqueo/reportes no satisfechos por esta matriz.
2026-10-08: CLR-001/004/009/010 → T08-C source sobre `fc858de42a5e021aab2f47ab68794a2519ff2cb5`: tipos/traductores v2, contexto/lifecycle, journal común de ocho mutaciones, claim before-POST, actor/SID/generación/cuarentena y consulta read-only. CLR-002/003/005 → sin fee de captura, semántica neta tras reversión y receipt seguido de refresh/resolución durable. [Evidencia C](wip/20261007-blackstore-cash-ledger-reconciliation-reports/evidence/CLR-T08-C-frontend-local-20261008.md). C implemented_pending_pg16_browser_ci_review; T08 parcial, T09 planned. Gates browser/PG16/crash/CI/reviews no se cierran por el PASS de unitarios/typecheck/build.
