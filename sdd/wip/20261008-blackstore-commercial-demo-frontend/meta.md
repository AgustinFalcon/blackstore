# BlackStore — frontend comercial demostrable

Estado: PLANNED. Fecha: 2026-10-08; base reconciliada 2026-10-20. Base vigente: PR #46 exacto `26020bb2278230b3c59426b49a480ff1daa49df3`.

Origen documental: PR #47 `4d2c9cd`, creado sobre el antiguo PR #45 `79c612c78160135df0e9011d5bcd93533d14d4e8`. El stack vigente incorpora PR #45 `bbd12c7d893e9df9a866dc2c20f1e253fc9435ff` y POSC-D de PR #46; esos antecedentes no acreditan ejecución de este demo. El diff propio de #47 contiene exclusivamente este WIP documental; implementación de #48/#49 fuera de alcance.

## Objetivo y precedencia

El usuario pide priorizar todas las pantallas y acciones del frontend para presentar al cliente, autorizando funcionalidad simulada antes del backend. La aceptación de este corte es experiencia comercial completa con repositorio demo stateful, no homologación ni integración real. No se atribuye al backend ningún resultado del simulador.

Este WIP complementa [UX v1](../20260923-blackstore-frontend-ux-system-v1/1-functional/spec.md), [PROJECT](../../PROJECT.md) y [PATTERNS](../../PATTERNS.md). La instrucción explícita del usuario autoriza una excepción a la antigua prohibición visual de DEMO únicamente bajo `/demo`: identificación discreta y permanente de datos simulados. No cambia las restricciones de las rutas operativas ni activa StoreCore, facturación o proveedores.

Compatibilidad de base: [POSC-D](../20261008-blackstore-pos-cart-explicit-payment/meta.md) conserva su diseño aprobado y B/T/U/E pendientes; [CLR](../20261007-blackstore-cash-ledger-reconciliation-reports/meta.md) conserva lifecycle, journal, contratos contables y sus gates propios. Ningún resultado demo satisface aceptación POSC/CLR ni hereda evidencia del snapshot combinado externo.

El código actual tiene siete rutas funcionales, pero su ticket individual y pago predeterminado no constituyen un POS comercial terminado. Este corte agrega dashboard, carrito multilínea, cobro explícito, comprobante, caja, ventas, productos, inventario, clientes y reportes con navegación y estados completos. El WIP backend-first de carrito continúa independiente; sus contratos no se reescriben para acomodar fixtures.

## Fuentes del contrato

- [Especificación funcional](1-functional/spec.md)
- [Arquitectura y aislamiento](2-technical/spec.md)
- [Plan de entrega](3-tasks/plan.md)
- [Inventario verificable de acciones](3-tasks/cta-inventory.md)
- [Aceptación](4-acceptance/checklist.md)

Gates iniciales: Design NOT_RUN; Implementation NOT_RUN; Review NOT_RUN; Browser NOT_RUN; Live integration OUT_OF_SCOPE. No cerrar hasta adjuntar evidencia al SHA implementado.
