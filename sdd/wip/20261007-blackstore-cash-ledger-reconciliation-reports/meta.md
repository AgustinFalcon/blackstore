# Libro de caja, arqueo y reportes por período

- Feature: `blackstore-cash-ledger-reconciliation-reports`.
- Backlog: TODO-007. Fecha: 2026-10-07. Idioma: español.
- Estado: `active`. GO de diseño: aprobado 2026-10-07 por revisión funcional/arquitectura Astra, factibilidad/Bugbot Sol y seguridad Sol después de resolver todos los hallazgos P1/P2. La implementación queda autorizada sólo por los cortes secuenciales del plan; ningún runtime se activa por este GO.
- Base documental: master con DCT PR #28 integrado en `b8ce3b1`; el arnés PG16/browser/reinicio tiene aprobación exact-head y CI, mientras los escenarios contables CLR conservan gates propios.
- Fuentes: AGENTS.md; sdd/STATUS.md, PROJECT.md y PATTERNS.md; piloto ADR-004/ADR-006; WIPs staff identity, durable commercial runtime y durable cash transaction boundary; decisiones de planificación Astra entregadas al coordinador.
- Alcance: ledger append-only productivo, arqueo sin ajuste automático, reconocimiento comercial separado, SHIFT/DAY filtrados y completos explícitamente.
- Dependencias: SID persistente/RBAC, runtime comercial durable y locks DCT. La infraestructura base PG16/browser/reinicio quedó acreditada por PR #28 y CI `37657282222`; cada corte contable conserva sus escenarios CLR específicos como evidencia todavía no ejecutada. V8 disponible observada después de V1–V7; volver a verificar numeración antes de crear la migración.
- Gates actuales: Design PASS; Implementation NOT_RUN; Integration NOT_RUN; Review PENDING para código/runtime; Homologation BLOCKED; Publication NOT_RUN.
- Estrategia: PR de SDD/GO y cuatro cortes de implementación. Sin código, migraciones ejecutadas, commit, push, deploy, publicación ni `/sdd.finish` en esta planificación.

StoreCore permanece fixture/disabled. Dos PostgreSQL, E2E live, fiscal, MP-LIVE-05, activación companion, facturación y Correo Argentino conservan gates externos independientes. La aceptación contable local no los habilita.
