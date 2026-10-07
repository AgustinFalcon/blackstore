# Progreso

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
