# Mostrador: carrito y cobro explícito

- Feature: `blackstore-pos-cart-explicit-payment` (POSC).
- Fecha: 2026-10-08. Estado: `proposed_pending_design_review`.
- Base exacta: BlackStore PR #45, `79c612c78160135df0e9011d5bcd93533d14d4e8`; branch documental `sdd/pos-cart-explicit-payment`.
- Alcance D: especificación solamente. Design REVIEW_PENDING; Implementation NOT_RUN; Integration NOT_RUN; Homologation BLOCKED; Publication NOT_RUN. No se heredan aprobaciones del head base.
- Fuentes inspeccionadas: AGENTS.md; STATUS/PROJECT/PATTERNS; CLR ADR-002 y contexto POS; `SaleV2Contracts.kt`, `SaleCommand.kt`, `SaleCommandFingerprint.kt`, `sale-command.ts`, `sale-admission-fingerprint.ts`, journal IndexedDB y pantalla de ticket; política previa ticket/payment.
- Dependencias: identidad staff, contexto POS autoritativo, lifecycle CLR, runtime durable, lectura de venta/cobertura/pagos y locks DCT del head base. La aceptación DCT del padre no satisface escenarios POSC.
- Decisión: backend multiline primero; después tipos/traductores/journal y finalmente UX de carrito y cobro. No implementar un carrito que emita múltiples reservas monolínea para simular una venta.
- Entregables: [funcional](1-functional/spec.md), [contratos](2-technical/spec.md), [secuencia y allowlist](3-tasks/plan.md), [tareas](3-tasks/tasks.json), [fixtures y aceptación](4-implementation/acceptance.md).
- StoreCore sigue dueño de catálogo/precios/costos/stock. Fixture explícita para pruebas locales; ninguna cantidad, precio ni integración live inventados. ML, fiscal y publicación excluidos.

Este documento propone contratos locales BlackStore. No modifica el OpenAPI StoreCore ni concede GO de implementación; el coordinador registra la revisión del SHA documental antes de B.
