# CLR-T08-B-POS — evidencia documental

Base exacta: `39aa9d6dad199d88328e9c3799911e355a9cbddd`. Branch: feat/cash-ledger-pos-context-sdd. Fecha: 2026-10-07.

Inspección read-only: StaffIdentity.kt/StaffSessionFilter.kt no aportan dispositivo; CounterController.WorkspaceResponse y JdbcWorkspaceQuery usan terminal del seed; V1 no vincula terminal/clientInstance/device; StoreCoreLoopbackConfiguration usa clientInstance configurado para catálogo; JdbcSaleMutationCommands admite cuádruple nueva sin binding. El detalle de venta publica identidad histórica, insuficiente para primera intención. No se inspeccionaron secretos.

[ADR-003](../2-technical/adr/ADR-003-pos-execution-context.md) propone contexto POS lógico provisionado, V11 y endpoint separados; exige validación transaccional, replay anterior al binding y conserva límites de perfiles/browser. Functional/technical/plan/tasks/progress/meta y trazabilidad actualizados. No hay cambios productivos ni migraciones creadas.

Validación documental: JSON parse, stats/IDs/dependencias/DAG, enlaces locales nuevos y git diff --check. Resultados registrados en entrega del commit; no equivalen a implementación. Review específica PENDING; backend/schema, PG16, browser y runtime NOT_RUN. T08-C blocked_on_pos_context, T08 parcial. Sin push, PR, activación, deploy ni gates externos nuevos.
