# Frontera transaccional durable de caja

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
