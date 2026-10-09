# BlackStore — frontend comercial demostrable

Estado: PLANNED. Fecha: 2026-10-08. Base: `79c612c78160135df0e9011d5bcd93533d14d4e8` (PR #45).

## Objetivo y precedencia

El usuario pide priorizar todas las pantallas y acciones del frontend para presentar al cliente, autorizando funcionalidad simulada antes del backend. La aceptación de este corte es experiencia comercial completa con repositorio demo stateful, no homologación ni integración real. No se atribuye al backend ningún resultado del simulador.

Este WIP complementa [UX v1](../20260923-blackstore-frontend-ux-system-v1/1-functional/spec.md), [PROJECT](../../PROJECT.md) y [PATTERNS](../../PATTERNS.md). La instrucción explícita del usuario autoriza una excepción a la antigua prohibición visual de DEMO únicamente bajo `/demo`: identificación discreta y permanente de datos simulados. No cambia las restricciones de las rutas operativas ni activa StoreCore, facturación o proveedores.

El código actual tiene siete rutas funcionales, pero su ticket individual y pago predeterminado no constituyen un POS comercial terminado. Este corte agrega dashboard, carrito multilínea, cobro explícito, comprobante, caja, ventas, productos, inventario, clientes y reportes con navegación y estados completos. El WIP backend-first de carrito continúa independiente; sus contratos no se reescriben para acomodar fixtures.

## Fuentes del contrato

- [Especificación funcional](1-functional/spec.md)
- [Arquitectura y aislamiento](2-technical/spec.md)
- [Plan de entrega](3-tasks/plan.md)
- [Inventario verificable de acciones](3-tasks/cta-inventory.md)
- [Aceptación](4-acceptance/checklist.md)

Gates iniciales: Design NOT_RUN; Implementation NOT_RUN; Review NOT_RUN; Browser NOT_RUN; Live integration OUT_OF_SCOPE. No cerrar hasta adjuntar evidencia al SHA implementado.
