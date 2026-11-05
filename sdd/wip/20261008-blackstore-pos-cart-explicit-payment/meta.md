# Mostrador: carrito y cobro explícito

- Feature: `blackstore-pos-cart-explicit-payment` (POSC).
- Fecha: 2026-10-08; diseño reconciliado 2026-10-20. Estado: `design_approved_implementation_pending`.
- Origen histórico: BlackStore PR #45 en `79c612c78160135df0e9011d5bcd93533d14d4e8`. Base vigente del stack documental: `bbd12c7d893e9df9a866dc2c20f1e253fc9435ff`; parent del cierre documental revisado: `1fec3d46c21f9c87e09ccfc437dc452719bc6142`; branch `sdd/pos-cart-explicit-payment`. POSC-B debe registrar su SHA exacto antes de elegir la siguiente migración Flyway.
- Alcance D cerrado: Design APPROVED por técnica, seguridad y producto/UX sin P0–P3 pendientes. Implementación, integración, CI, homologación y publicación permanecen NOT_RUN/BLOCKED en este head documental; sus evidencias se incorporan en B/T/U/E sobre sus respectivos heads, no retroactivamente aquí.
- Fuentes inspeccionadas: AGENTS.md; STATUS/PROJECT/PATTERNS; CLR ADR-002 y contexto POS; `SaleV2Contracts.kt`, `SaleCommand.kt`, `SaleCommandFingerprint.kt`, `sale-command.ts`, `sale-admission-fingerprint.ts`, journal IndexedDB y pantalla de ticket; política previa ticket/payment. Revisión coordinada Astra 2026-10-20: técnica, seguridad y producto/UX; seis P2 y una precisión P3 incorporados. Re-review: APPROVED en los tres gates, sin P0–P3.
- Dependencias: identidad staff, contexto POS autoritativo, lifecycle CLR, runtime durable, lectura de venta/cobertura/pagos y locks DCT del head base. La aceptación DCT del padre no satisface escenarios POSC.
- Decisión: backend multiline primero; después tipos/traductores/journal y finalmente UX de carrito y cobro. No implementar un carrito que emita múltiples reservas monolínea para simular una venta.
- Entregables: [funcional](1-functional/spec.md), [contratos](2-technical/spec.md), [secuencia y allowlist](3-tasks/plan.md), [tareas](3-tasks/tasks.json), [fixtures y aceptación](4-implementation/acceptance.md).
- StoreCore sigue dueño de catálogo/precios/costos/stock. Fixture explícita para pruebas locales; ninguna cantidad, precio ni integración live inventados. ML, fiscal y publicación excluidos.

Este documento propone contratos locales BlackStore. No modifica el OpenAPI StoreCore ni concede GO de implementación; el coordinador registra la revisión del SHA documental antes de B.
