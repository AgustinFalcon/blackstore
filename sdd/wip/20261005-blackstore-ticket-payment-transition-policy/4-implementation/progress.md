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
- [x] Backend: `gradlew test` oficial verde, 102 tests, luego de alinear el fixture de contrato con el digest canónico.
- [x] Frontend: `npm run build` verde y 22 specs Jasmine de tipos/política/mapper/pasos ejecutados en Node, verdes.
- [ ] Los specs Angular de componente están escritos y typecheckean, pero ChromeHeadless local en Windows aborta antes de assertions por el sandbox/GPU del host; GitHub CI Linux será la evidencia autoritativa.
- [ ] CI final y revisión posterior a implementación.

La implementación agrega la garantía local fail-closed de transición, saldo, correlación e aislamiento por venta en una única instancia. No se afirma coordinación multiinstancia ni atomicidad global. No se cambió STATUS ni el contrato StoreCore. No se autoriza integración operativa; disabled/fixture/kill switch/canary 0 permanecen. Recovery durable, atomicidad transaccional/global y reportes están fuera; recovery local existente se preserva. Los tests locales no sustituyen CI ni las dos revisiones exactas del código final. Sin `/sdd.finish`.
