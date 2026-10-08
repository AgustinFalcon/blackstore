# Libro de caja, arqueo y reportes por período

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
