# Frontera transaccional durable de caja

Último P2 Security corregido 2026-10-07: titular ACTIVE + CASHIER exactamente, separado de permiso actor SUPERVISOR/OWNER. Enfocada22/amplia FINAL153 PASS sobre source post-fix, cero failures/errors/skips, mismas seis exclusiones Docker; teardown75632/5441/children/launcher PASS. Antecedente151 sustituido; nueva review/PG16/browser/CI pendientes.

Validación final actual 2026-10-07: amplia nativa151 PASS/39 suites/cero fallos/errores/skips sobre source posterior a residual actor/owner; enfocada20 PASS, seis exclusiones Docker. Teardown final5020/5441/children/launcher PASS. Reemplaza antecedente149, no cierra gates PG16/browser/CI/review global.

Última extensión 2026-10-07: actor/owner sin autoridad también llevan procedencia Authorization (paridad con eligibleCashier anterior); enfocada final20 PASS, teardown PID50428/5441 PASS. Amplia149 es antecedente anterior a esta extensión; nueva review/CI/browser/PG16 pendientes.

- Feature: `durable-cash-transaction-boundary`.
- Fecha: 2026-10-06. Idioma: español.
- Branch: `fix/durable-cash-transaction-boundary`.
- Base observada: `e6a47a32b5f21b9f020fc1f9f23a73a19e5ed4a7`.
- Estado: `implementation_partial`; diseño aprobado por GPT-6.1 Sol medium (`/root/blackstore_cash_sdd_review`) sobre la base exacta indicada, tras resolver SID/HTTP y diagnóstico del writer; sin P0–P3. Dos P2 de implementación corregidos y probados: backend local 148 tests PASS el 2026-10-07; suite enfocada 17 PASS; UI typecheck PASS. Browser/reinicio y build/tests UI bloqueados por entorno; PG16/CI y nueva revisión final de implementación pendientes. Recursos propios apagados.
- Revisión documental R1: corregidos contrato SID/HTTP con visibilidad previa a conflicto y diagnóstico del writer (ya posee condición `OPEN` y rowcount); nueva revisión Sol `APPROVED`, GO de implementación informado por el coordinador.
- Destino propuesto: `master`, corte core local y fail-closed.
- Fuentes: `AGENTS.md`, documentos canónicos SDD, WIPs `20261006-blackstore-staff-identity`, `20261006-blackstore-durable-commercial-runtime`, piloto `ADR-004-cash-ledgers` y `TASK-004-cash-sessions`; fuentes de código detalladas en la especificación técnica.
- Método: Falcon `sdd-workflow`, `e2e-evidence`, política de dominio cerrado y estándares Spring/Angular. Artefactos versionados sin rutas privadas, secretos ni configuración del equipo.
- Dependencias: identidad staff y runtime durable existentes; este corte no amplía sus aprobaciones.
- Gates: Implementation `PARTIAL` (backend PASS, UI typecheck PASS; UI build/tests BLOCKED); Integration `BLOCKED` (PG18 local/crash PASS, browser BLOCKED, PG16 pendiente); Review implementación `FAIL` (dos P2 informados, corregidos; nueva revisión pendiente; diseño APPROVED); Homologation `BLOCKED`; Publication `NOT_RUN`.

Objetivo: apertura/cierre con auditoría atómicos y egresos serializados con cierre mediante PostgreSQL. Libro de caja, arqueo, fórmulas de reportes, StoreCore live y fiscal permanecen pendientes fuera de este corte. No `/sdd.finish`.

Actualización exact-head 2026-10-07: P2 AUTHORIZATION_DENIED corregido con procedencia cerrada y escritura independiente del comando rechazado. Enfocada18/amplia nativa149 PASS; teardown PID65748/5441 PASS. Nueva review del source corregido pendiente; no supersede gates browser/UI/PG16/CI ni aprobación global. Evidencia `evidence/DCT-authorization-denial-resolution.md`.
