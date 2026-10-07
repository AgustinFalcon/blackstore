# Progreso

## 2026-10-07 — incremento browser reproducible, aceptación bloqueada

Harness Playwright Chromium/PG16 efímero/JAR real reiniciable, cash-browser-restart/denials, job dct-browser y docs agregados; scheduler deshabilitado explícitamente sólo en dos tests HTTP PG y fixture. Typechecks/discovery/compileTestKotlin PASS; domain/application54 tests/11 suites/cero failures/errors/skips PASS. Dos HTTP PG compilan, runtime pendiente. Ejecución browser real dos FAIL de setup por acceso Docker denegado, cero assertions/recursos runtime. Build Angular local bloqueado por acceso al ancestro C:\. Review/CI nuevos NOT_RUN; conserva WIP/T05/T06 parciales. Evidencia DCT-browser-harness-20261007.md; resultados actuales no sustituyen la aceptación pendiente ni revisiones del corte mergeado.

## 2026-10-07 — merge core y aceptación residual

[PR #26](https://github.com/AgustinFalcon/blackstore/pull/26) MERGED: head `caab0a9`, merge `a9887a3` en master. Dos revisiones independientes GPT-6.1 Sol sobre el head exacto (bugs y seguridad/arquitectura) APPROVED. [CI PR 37634224683](https://github.com/AgustinFalcon/blackstore/actions/runs/37634224683) verde; [CI post-merge master 37634797108](https://github.com/AgustinFalcon/blackstore/actions/runs/37634797108) verde (frontend 34 s, backend 3 min 43 s).

La regresión local final de este source sigue siendo 153 tests / 39 suites / 0 failures/errors/skips, con seis clases Docker-only excluidas y PostgreSQL 18; enfocada 22 PASS, teardown PASS. Las entradas siguientes conservan la historia y los fallos con su disposición; sus menciones de review/CI pendientes describen aquel momento y quedan sustituidas por esta entrada. Implementation PASS para el corte integrado, Review APPROVED; T02–T04 done. T05 y T06 siguen parciales: PostgreSQL 16 y browser real con reinicio DCT pendientes. El CI verde no convierte esos escenarios en PASS ni amplía homologación. WIP `merged_partial_acceptance`, sin archivo, /sdd.finish, tag ni publicación.

## 2026-10-07 — P2 Security titular CASHIER estricto

JDBC/memory dejan de usar permiso CashSessionOpen como elegibilidad de titular: exige ACTIVE y rol exactamente CASHIER, conservando permiso de actor aparte y NotVisible/Authorization con una auditoría independiente. Matriz memory/HTTP/PG supervisor/owner/inactivo/missing más positivos actor supervisor/owner sobre cajero activo. Enfocada22 PASS; amplia FINAL post-fix153 PASS/39 suites/0 failures/errors/skips, BUILD SUCCESSFUL1m33, mismas seis exclusiones Docker. Antecedente151 sustituido; PG18, PG16/browser/CI pendientes. Teardown main75632/5441/six children/launcher PASS tras completion explícita. Nueva review pendiente; sin commit/push ni schema/grants nuevos. Evidencia DCT-authorization-denial-resolution.md.

## 2026-10-07 — amplia nativa FINAL del source residual

Sin cambio de producción: suite amplia nativa compatible repetida sobre actor/owner Authorization actuales. BUILD SUCCESSFUL1m29; 151 tests/39 suites/0 failures/errors/skips. Mismas seis exclusiones Docker declaradas; PG18, no certificación PG16/browser/CI. Enfocada20 PASS. Teardown main5020, listener5441, seis crash children y launcher PASS después del completion explícito; no backend/frontend/browser. Evidencia DCT-authorization-denial-resolution.md. Amplia149 queda antecedente, reemplazada por151 sobre source final; review/CI/gates globales pendientes, no commit/push.

## 2026-10-07 — procedencia de autoridad durable completada

Revisión residual confirmó que actor inactivo/missing y owner inactivo/missing/rol inelegible son denegaciones de autoridad: el open anterior auditaba eligibleCashier=false. Marcados Authorization en ambos adapters, con tests nuevos memory/PG application (una denegación independiente por actor, cero caja/success audit, igualdad exacta). Enfocada final20 PASS/0 failures/errors/skips BUILD SUCCESSFUL1m55. Amplia149 del source previo conservada como antecedente, no rerun de este incremento solicitado enfocado. Teardown PID50428/5441 y seis hijos PASS; nueva review pendiente, sin commit/push. Ver evidence/DCT-authorization-denial-resolution.md.

## 2026-10-07 — P2 exact-head AUTHORIZATION_DENIED restaurado

Procedencia cerrada `CashRejectionSource` default Mutation, Authorization desde policy.authorize y revalidación de actor/owner en adapters JDBC/memory; application registra denegación independiente después de rollback/retorno antes del fallo HTTP. No marca validación común, Conflict/Unavailable; preserva 404/409 y éxito atómico. Unit/provenance + resultados existentes con igualdad exacta + MockMvc/PG caja ajena/override/permiso y cardinalidades. Enfocada 18 PASS; amplia nativa compatible 149 PASS/39 suites/0 failures/errors/skips, BUILD SUCCESSFUL 1m28. Primera enfocada 17 PASS/1 FAIL por expectation de auditor HTTP (filtro previo), conservada con disposición; se agregó prueba application sin relajar asserts HTTP. Evidencia `evidence/DCT-authorization-denial-resolution.md`. Seis clases Docker excluidas, PG16/browser/CI pendientes; nueva review de corrección pendiente. PostgreSQL PID65748 detenido tras completion explícita; pg_ctl sin servidor, pg_isready5441 sin respuesta, no backend/frontend/browser iniciados. Sin commit/push.

## 2026-10-07 — dos P2 corregidos, regresión y teardown

Reemplazado escape JSON manual de auditoría por Jackson; prueba PG real conserva newline/tab/control/comillas/barra y verifica egreso/auditoría exactamente una vez con payload válido. Memoria ahora considera todos los blockers antes de revelar Conflict: cualquier oculto produce NotVisible, independiente del orden. Tres tests memory y paridad PG en ambos órdenes.

Suite enfocada 17 PASS. Suite amplia nativa compatible final 148 PASS, 0 failures/errors/skips, BUILD SUCCESSFUL 1m32. Se retiene el run suspendido durante la noche: 148 tests, 147 PASS/1 FAIL HTTP503 al apagar DB antes de completion del runner; repetición sin cambios de código, sin ocultar el fallo. Ver `evidence/DCT-review-p2-resolution.md`. Seis clases Docker siguen excluidas; PG16/browser/CI/UI permanecen pendientes.

Dos revisiones independientes GPT-6.1 Sol medium devolvieron **APPROVED** sin P0–P3. La revisión Bugbot confirmó ambos P2 y la cobertura memory/PG en ambos órdenes; la revisión Security/Architecture confirmó visibilidad 404/409, autoridad durable, atomicidad, locks, `SET LOCAL ROLE`, IDs y UI sin replay de POST. Ambas verificaron 38 suites XML / 148 tests / 0 failures/errors/skips y no iniciaron servicios. Esta aprobación cubre el source local revisado; todavía se exige review exact-head después del commit y CI hospedado antes de merge. Tasks conservan estado parcial, sin cierre global.

PostgreSQL main PID18644 apagado después de terminar el run; PID65668 anterior también ausente, listener5441 ausente y seis hijos terminados. No browser/backend/frontend nuevo, ni commit/push/PR/merge/migración/grants adicionales. Recursos del cluster se conservan apagados para evidencia, sin `/sdd.finish`.

## 2026-10-06 — implementación local y teardown

Diseño R1 aprobado por GPT-6.1 Sol medium sin P0–P3. Backend implementado con puerto atómico, transacción/conexión común, lock compartido, autoridad durable, dinero exacto, IDs DB y resultados HTTP cerrados. Se retiraron APIs de escritura partida; se conservaron condición OPEN y rowcount. Ambos índices únicos existentes terminal/cajero se reconocen sólo por SQLSTATE/constraint con visibilidad posterior al rollback. No migración/grants nuevos.

Regresión final nativa compatible: 143 tests PASS, 0 fallos/errores/skips, incluidos ocho tests PG y seis crash de procesos hijos. Seis clases previas Docker excluidas por pipe denegado; no suite completa ni certificación PG16. Ver `evidence/DCT-T05-postgres.md`, que retiene fallos previos de entorno/fixtures y su disposición.

UI typecheck PASS; build/tests UI y browser real BLOCKED por entorno. Browser integrado no soportó visibilidad desde subagente y creación oculta colgó hasta aborto del usuario, sin assertions; ver `evidence/DCT-T06-browser-restart.md`. No se ejecutó reinicio Spring/browser ni se infirió PASS desde MockMvc. Revisión final/CI hospedado pendiente. PostgreSQL PID70556 detenido, puerto5441 y seis hijos ausentes; backend/frontend no iniciados. Recursos históricos se conservaron. Sin commit/push/PR/merge/publicación ni `/sdd.finish`.

## 2026-10-06 — corrección documental Sol R1

Se corrigieron exactamente dos hallazgos: (1) SID hoy da 404 a egreso no abierto; DCT separa visibilidad/elegibilidad para 409 sólo en caja visible y mantiene 404 indistinguible en ausente/ajena, con resultados cerrados, targets backend/Angular y pruebas HTTP/UI explícitos; (2) el writer ya filtra `OPEN` y exige rowcount uno, por lo que se conservan esas defensas y se ubica la brecha en transacción/auditoría y autorización separadas. Tasks/plan/trazabilidad actualizados; ninguna implementación ni test ejecutado. Nueva revisión Sol pendiente, estado `ready_for_sol_review`. Validación: JSON parseable y diff sin errores de whitespace; no commit/push.

## 2026-10-06 — especificación, sin implementación

Base inspeccionada `e6a47a32b5f21b9f020fc1f9f23a73a19e5ed4a7`, branch `fix/durable-cash-transaction-boundary`. Se documentó la brecha autocommit/auditoría y egreso/cierre, se definieron seis tareas y requisitos DCT-001..007. No se modificó código productivo, tests ni migraciones. No se ejecutaron pruebas de runtime ni se crearon procesos/DB/browser de aceptación. No hubo commit, push, publicación ni activación.

Evidencia disponible: lectura de fuentes y validación documental; comportamiento requerido de este corte sigue missing. Implementation/Integration/Review `NOT_RUN`; Homologation `BLOCKED`; Publication `NOT_RUN`. Falta revisión del SDD antes de construir, luego las pruebas y revisiones descritas en el plan. Sin afirmación de caja contable, arqueo o reportes correctos.
