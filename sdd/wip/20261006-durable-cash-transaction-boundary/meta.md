# Frontera transaccional durable de caja

- Feature: `durable-cash-transaction-boundary`.
- Fecha: 2026-10-06; actualización: 2026-10-07. Idioma: español.
- Branch: `fix/durable-cash-transaction-boundary`.
- Base observada: `e6a47a32b5f21b9f020fc1f9f23a73a19e5ed4a7`.
- Estado: `merged_partial_acceptance`. [PR #26](https://github.com/AgustinFalcon/blackstore/pull/26) MERGED, head `caab0a9`, merge `a9887a3` en master. El merge core no equivale a aceptación integral ni cierre del WIP.
- Review: dos revisiones independientes GPT-6.1 Sol sobre el head exacto `caab0a9`, bugs y seguridad/arquitectura, APPROVED; diseño T01 aprobado tras R1. Hallazgos de implementación corregidos antes de estas revisiones.
- Validación local final: 153 tests / 39 suites / 0 failures / 0 errors / 0 skipped, seis clases Docker-only excluidas; enfocada 22 PASS; PostgreSQL 18 local y teardown PASS. No certifica PostgreSQL 16.
- CI: [PR run 37634224683](https://github.com/AgustinFalcon/blackstore/actions/runs/37634224683) verde sobre el head; [post-merge master run 37634797108](https://github.com/AgustinFalcon/blackstore/actions/runs/37634797108) verde sobre `a9887a3`, frontend 34 s y backend 3 min 43 s.
- Fuentes: AGENTS.md, documentos canónicos SDD, WIPs de identidad staff y runtime comercial durable, piloto ADR-004-cash-ledgers y TASK-004-cash-sessions; fuentes de código en la especificación técnica.
- Dependencias: identidad staff y runtime durable incorporados antes de este corte; DCT no amplía sus aprobaciones.
- Gates: Implementation PASS para el corte mergeado con regresión local y CI; Integration PARTIAL (PG18/crash/HTTP PASS; PG16 y browser con reinicio DCT pendientes); Review APPROVED sobre head exacto; Homologation BLOCKED; Publication NOT_RUN.

Incremento de aceptación 2026-10-07 (source local sin commit): harness Playwright/PG16/JAR y job dct-browser implementados. Discovery/typechecks PASS; intento real BLOCKED antes de assertions por pipe Docker denegado. Review exact-head/CI nuevos pendientes: no hereda aprobaciones de caab0a9. Ver evidence/DCT-browser-harness-20261007.md y docs/testing/dct-browser-restart.md; T05/T06 e Integration parciales.

Apertura/cierre y egresos comparten transacción, auditoría y lock durable con revalidación de autoridad. El titular exige ACTIVE y exactamente CASHIER; el permiso de actor SUPERVISOR/OWNER se conserva por separado. Las denegaciones de autoridad conservan procedencia cerrada y auditoría independiente después del rechazo. Evidencias históricas y fallos previos permanecen en evidence/; la validación de 153 tests sustituye las regresiones anteriores para el source final.

Libro de caja, arqueo, fórmulas de reportes, StoreCore live y fiscal siguen fuera de este corte. Browser/reinicio DCT no ejecutado con aceptación; la evidencia browser de identidad/runtime previo no lo sustituye. No /sdd.finish, archivo, tag, deploy ni publicación.
