# Evidencia CLR — antecedentes y gates por head

Cada archivo acredita sólo el source o snapshot que identifica. Los resultados
del combinado CLR-T09 pertenecen a
`115fad53006b55d27b021883bf49153a3de050fa`; no acreditan el corte #45, que
excluye POSC/Demo y varios escenarios. El head separado necesita evidencia,
review y CI propios. No hay secretos/cookies/CSRF ni tráfico live.

- Corte vigente PR #45: [validación local y correcciones 2026-10-20](CLR-T09-pr45-local-20261020.md). El candidato previo al commit acredita backend 293/293, frontend 242/242, typechecks, build y browser PG16 8/8. Las correcciones posteriores registran su validación focal propia; no reejecutaron esa aceptación integral. Respuesta perdida, cross-tab mismo SID y RELEASE_PENDING siguen NOT_RUN; reviews exact-head, CI, merge y homologación pendientes.

- Antecedente combinado CLR-T09: [validación final 2026-10-20](CLR-T09-combined-final-local-20261020.md). Corresponde al snapshot `115fad53006b55d27b021883bf49153a3de050fa`, no al head #45. Incluye POSC/Demo y no transfiere PASS o APPROVE. Sin commit, PR, merge, homologación ni live.

- Antecedente externo CLR-T09: [PostgreSQL 16 y browser 2026-10-16](CLR-T09-cash-browser-pg16-local-20261016.md). El overlay histórico incluye fence V12 endurecido, caja/ventas/reportes, reinicio, pérdida de respuesta, rotación SID, legacy aislado, PAUSED y teardown. Sus resultados no acreditan #45. Sin homologación, activación live, publicación, PR ni merge.

- Estado local integrado T08-D: [frontend 2026-10-08](CLR-T08-D-frontend-local-20261008.md), source b8024dcc0292897aa7a77f96e9ed9e25d63d54ea. npm ci/typecheck/build y 223/223 Karma PASS; audit productivo 0. Acredita sólo unitarios/integración HTTP con test doubles: PG16/browser/crash/restart/CI/reviews pendientes. Historial de addenda/fallos conservado.

- CLR-T08-D: [documental 2026-10-08](CLR-T08-D-sdd-20261008.md), base exacta fc858de42a5e021aab2f47ab68794a2519ff2cb5, ADR-004 proposed/review específica pendiente. No implementación ni PASS PG16/browser/CI heredados. D-BACKEND/T08-C/T09 deben registrar refund→capture y receipt/gasto/settlement/postings del mismo snapshot, cutoff/asOf/version/completitud, autoridad/opacidad, pérdida de respuesta/restart y ausencia de POST automático con source/build/PG major y teardown propios.

- CLR-T01: PASS documental 2026-10-07. Revisiones Astra funcional/arquitectura, Sol factibilidad/Bugbot y Sol seguridad; NO-GO inicial y follow-ups conservaron los hallazgos, todos resueltos antes del GO. La evidencia vive en la conversación coordinadora hasta materializarse en el PR SDD exact-head.
- Prerrequisito DCT base: PR #28, SHA revisado `bca02bfcf0c0f282d95696c463318e1cea78ee50`, merge `b8ce3b1c9037bf0f557a6efdf658955e70050f9d`; Bugbot y Security APPROVED sin P0–P3; CI PR `37657282222` y CI posmerge master `37658142335`, ambos con backend, frontend y browser PG16/reinicio SUCCESS. Esto acredita el arnés, no los escenarios contables CLR todavía NOT_RUN.
- CLR-T02/T03: tipos/traductores/políticas; PG16 clean/upgrade poblada; schema/grants/immutable; legacy preservado y preflight.
- CLR-T04/T05/T06: filas de pago/gasto/posting/receipt/audit/reconciliation, replay/mismatch, barreras de carreras, crash del PID propio pre/postcommit, consulta tras reinicio, pool roles, kill switch y forward recovery.
- CLR-T05 local: `CLR-T05-local-20261007.md` acredita cierre/arqueo/lifecycle/reconocimiento y carreras en PostgreSQL 18; no acredita PostgreSQL 16 ni crash/reinicio T06.
- CLR-T07: dataset con dos cajas/días/medios/ventas pendientes, DST y fronteras, snapshots/cutoffs, no fan-out, complete/incomplete y filtros de autoridad.
- CLR-T08/T09: browser SID + backend exacto + PG16, flujos funcionales, reinicio, screenshots/traces sanitizados; CI y reviews exact-head independientes.
- CLR-T08-B-POS: [backend local 2026-10-08](CLR-T08-B-POS-backend-local-20261008.md), contexto cerrado/HTTP y fixtures V11/provisión/replay/carreras preparados; PG16 bloqueado antes de assertions por Docker no disponible, browser/CI/reviews pendientes. No hereda aprobación de B ni activa runtime.

Cada ejecución identifica recursos propios (DB/schema/puertos/PID/directorio), teardown confirmado y cualquier recurso retenido con razón. Nunca terminar procesos ajenos ni limpiar directorios amplios. Evidencia de fixture/MockMvc/discovery se etiqueta como tal; sólo browser real y PG16 ejecutados cierran sus gates. Fallos y bloqueos se conservan, sin reescribirlos como PASS.
