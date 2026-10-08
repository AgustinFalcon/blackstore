# Evidencia requerida — diseño aprobado, runtime CLR todavía no ejecutado

Esta carpeta no acredita PASS. Conservar por CLR-Txx la revisión/base exacta, comando o harness, build, Flyway versions, PostgreSQL major 16, roles runtime, escenarios/assertions, resultado, fallos y artefactos sanitizados. No secretos/cookies/CSRF ni tráfico live.

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
