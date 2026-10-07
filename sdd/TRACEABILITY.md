# RTM — `blackstore-pilot`

## Estado vigente — 2026-10-07

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
