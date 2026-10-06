# Progreso y evidencia

Fecha 2026-10-05. Base exacta `a92ec07868638e312e568f3dc36837ea78cdde44`.

- [x] Leídos `AGENTS.md`, `sdd/STATUS.md`, `sdd/PROJECT.md`, `sdd/PATTERNS.md` y convenciones WIP.
- [x] Inspección estática de UI de ticket, saga/PaymentBook, controller de ventas y persistencia de pagos para anclar alcance y riesgos.
- [x] Draft funcional/técnico, trazabilidad TP-001..TP-006, plan, tareas y matriz T01..T12 creados.
- [x] Dos reviews históricos devolvieron **CHANGES_REQUESTED**; no constituyen GO ni aprobación del draft corregido.
- [x] Draft corregido en respuesta: coordinación local compartida con carreras de barreras; identidad/cuádruple inequívoca y cardinalidad fail-closed; DTO/snapshot y correlación exacta; guard UI previo al UUID; política NUMERIC(14,2); decisiones separadas de comando/recovery/replay; Unknown Kotlin HTTP/JDBC e historial de reversas legible.
- [x] T01..T12 y tareas ampliadas para cubrir esos hallazgos. Esto es cobertura planificada, no pruebas ejecutadas.
- [x] Re-reviews funcional/arquitectura y seguridad del draft corregido: **GO/APROBADO** para implementar únicamente el alcance local sobre `a92ec078`; revisión estática, sin acreditar código ni tests.
- [x] TP-001..TP-006 implementados en backend y frontend sobre la base exacta del corte.
- [x] Backend local: 101 tests verdes; dos suites de infraestructura no inicializaron por Docker no disponible en el host. Las suites modificadas `PaymentTransitionPolicyTest` y `StoreCoreDurableSagaTest` se repitieron y quedaron verdes.
- [x] Frontend local limpio: `npm ci`, `npm run build` y `npx tsc -p tsconfig.spec.json --noEmit` verdes.
- [x] ChromeHeadless local en Windows compiló los bundles y abortó antes de assertions por el subsistema crypt/GPU del host; GitHub CI Linux ejecutó la evidencia autoritativa.
- [x] GitHub Actions `37397515518` sobre `2d97a26d309c24cd08e2502b4d0240ca342c6e8f`: jobs `backend` y `frontend` verdes, incluyendo las suites Docker/Testcontainers/JDBC.
- [x] Revisiones independientes finales sobre ese commit: bugs, seguridad y conformidad SDD **APROBADAS**, sin hallazgos P0-P3.
- [ ] Merge de PR #23 y repetición de CI sobre `master`.

La implementación agrega la garantía local fail-closed de transición, saldo, correlación e aislamiento por venta en una única instancia. No se afirma coordinación multiinstancia ni atomicidad global. No se cambió STATUS ni el contrato StoreCore. No se autoriza integración operativa; disabled/fixture/kill switch/canary 0 permanecen. Recovery durable, atomicidad transaccional/global y reportes están fuera; recovery local existente se preserva. El corte sigue en WIP y no habilita `/sdd.finish` ni homologación externa.
