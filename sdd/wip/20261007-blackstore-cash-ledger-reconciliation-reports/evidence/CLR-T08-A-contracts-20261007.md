# CLR-T08-A — propuesta contractual, 2026-10-07

Base exacta `9e6e2cb0522bf2e001245a1593edeea2bb3136a6`, branch `feat/cash-ledger-contracts-t08a`, worktree sibling `blackstore-clr-contracts-t08a`. Sólo documentación. El SHA resultante lo identifica el commit que contiene esta evidencia; no registrar una revisión o un SHA circular inventado.

## Alcance y fuente

Inspección read-only de AGENTS, STATUS, PROJECT, PATTERNS, WIP CLR, LocalSaleSagaService/JdbcSaleRecordStore/DurableSaleCommandFlow, AccountingV2Controller/AccountingCommand/AccountingRuntimeQuery, lifecycle admission/V8/V9, StaffIdentity, SessionStore y AccountingCommandsStore sobre la base. Hallazgos residuales coinciden con [evidencia T08](CLR-T08-operations-local-20261007.md).

[ADR-002](../2-technical/adr/ADR-002-sale-v2-lifecycle-command-journal.md) propone contratos v2 de saga/Accepted, GET de lifecycle, persistencia y rehidratación del commandId, identidad/tab, fee y matriz de permisos/replay/crash. Specs, plan, tareas, progress, meta y trazabilidad referencian la propuesta. No endpoints, migraciones ni cambios de código implementados. V10 sólo es nombre propuesto pendiente de verificar al integrar.

## Validación documental

PASS: validación Node read-only de JSON y consistencia (12 tareas, 1 done, IDs únicos, dependencias existentes y grafo sin ciclos); resolución de los 18 links locales Markdown en los 9 documentos nuevos/modificados, cero destinos inexistentes; comprobación de alcance sólo `sdd/`. `git diff --check` PASS. Estas validaciones no acreditan semántica runtime ni aprobación de diseño.

Un primer intento directo de Git fue rechazado por ownership distinto de la worktree (SIDs Windows); se repitió con `git -c safe.directory=C:/Users/agustin/Documents/Codex/2026-10-01/bien/work/blackstore-clr-contracts-t08a diff --check`, salida 0. Excepción sólo por comando y ruta exacta, sin modificar config global. Advertencias LF→CRLF de Git no son errores de whitespace ni cambios productivos. Los tests backend/frontend/PG/browser no se ejecutaron porque este corte sólo cambia documentación; sus gates siguen pendientes.

## GO DE DISEÑO y revisiones

2026-10-07: el usuario autorizó explícitamente el GO DE DISEÑO para continuar. El coordinador comunicó APPROVE documental y APPROVE Security sobre exact head `0b4cddc7f26ec6965ad81443ec4cde74aadd2ce5`. Se registra ese resultado con su alcance y SHA originales, sin inventar una revisión de código ni una nueva review sobre el SHA del amend. El amend sólo actualiza estado/trazabilidad del GO; los contratos revisados no cambian. CLR-T08-A done; ADR-002 accepted; T08-B/C planned y habilitados para continuar con sus propios gates.

Implementación, runtime, activación y homologación NO aprobados por este GO. T08 permanece parcial; PG16/browser/CI/reviews de implementación y gates externos conservan su estado. El registro de aprobación no modifica ni ejecuta V10, backend, frontend o configuración.

Revalidación del amend: JSON/IDs/dependencias/DAG PASS, 12 tareas/2 done; 19 vínculos locales en los 9 documentos del corte resueltos, cero errores; alcance exclusivamente SDD y diff-check PASS. Los valores 1 done/18 links anteriores corresponden a la validación previa al registro del GO.

## Gates

T08-A done; GO específico de diseño PASS. T08-B/C planned, T08 parcial y T09 planned. Tests de contratos nuevos, PG16, browser/restart/crash, build productivo nuevo, CI y reviews exact-head de implementación NOT_RUN/PENDING. Evidencia previa permanece sin promoverla. Runtime operativo PRE_ACTIVATION; sin activación, push, PR, homologación/publicación, archivo ni `/sdd.finish`.
