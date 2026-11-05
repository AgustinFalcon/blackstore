# Mostrador: carrito y cobro explícito

- Feature: `blackstore-pos-cart-explicit-payment` (POSC).
- Fecha: 2026-10-08; diseño reconciliado 2026-10-20. Estado: `posc_b_review_approved_ci_pending` (2026-11-05).
- Origen histórico: BlackStore PR #45 en `79c612c78160135df0e9011d5bcd93533d14d4e8`. POSC-B parte de `3a6a520555d499fceda835cf573f25921c922e5f` (master tras CLR), branch `codex/blackstore-posc-b`; eligió V13 después de comprobar V1–V12. El SHA exacto se registra al crear el commit revisable.
- Alcance D y B cerrados. POSC-B tiene implementación local y gates backend PASS; bug/producto y seguridad Astra aprobaron `bcdb31f4839dedf0324ba85ccf020a4607cfc019` sin P0–P3. CI e integración siguen pendientes. POSC-T/U/E, homologación y publicación no se atribuyen a este corte.
- Fuentes inspeccionadas: AGENTS.md; STATUS/PROJECT/PATTERNS; CLR ADR-002 y contexto POS; `SaleV2Contracts.kt`, `SaleCommand.kt`, `SaleCommandFingerprint.kt`, `sale-command.ts`, `sale-admission-fingerprint.ts`, journal IndexedDB y pantalla de ticket; política previa ticket/payment. Revisión coordinada Astra 2026-10-20: técnica, seguridad y producto/UX; seis P2 y una precisión P3 incorporados. Re-review: APPROVED en los tres gates, sin P0–P3.
- Dependencias: identidad staff, contexto POS autoritativo, lifecycle CLR, runtime durable, lectura de venta/cobertura/pagos y locks DCT del head base. La aceptación DCT del padre no satisface escenarios POSC.
- Decisión: backend multiline primero; después tipos/traductores/journal y finalmente UX de carrito y cobro. No implementar un carrito que emita múltiples reservas monolínea para simular una venta.
- Entregables: [funcional](1-functional/spec.md), [contratos](2-technical/spec.md), [secuencia y allowlist](3-tasks/plan.md), [tareas](3-tasks/tasks.json), [fixtures y aceptación](4-implementation/acceptance.md).
- Evidencia POSC-B: [ejecución local 2026-11-05](4-implementation/posc-b-evidence.md).
- StoreCore sigue dueño de catálogo/precios/costos/stock. Fixture explícita para pruebas locales; ninguna cantidad, precio ni integración live inventados. ML, fiscal y publicación excluidos.

Este documento propone contratos locales BlackStore. No modifica el OpenAPI StoreCore ni concede GO de implementación; el coordinador registra la revisión del SHA documental antes de B.
