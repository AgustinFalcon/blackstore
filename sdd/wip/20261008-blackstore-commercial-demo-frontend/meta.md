# BlackStore — frontend comercial demostrable

Estado: FRONTEND_DEMO_APPROVED_LOCAL. Fecha: 2026-10-08; cierre local revalidado 2026-11-05. Código exacto aprobado: `15655610745d100f7692e3b65199618b0c0004c4`. Los gates POSC/live, integraciones, deploy y homologación permanecen separados y no se consideran acreditados.

Incremento de cierre 2026-11-05: el addendum
`2-technical/final-frontend-closure-addendum.md` y el plan
`3-tasks/final-frontend-closure-20261105.md` incorporan los gaps detectados sobre
`358b9bc`. El alcance sigue siendo POS Demo Cajero/Encargada; no se reclasifica como
ecommerce comprador/admin ni se heredan gates POSC/live.

Origen documental: PR #47 `4d2c9cd`, creado sobre el antiguo PR #45 `79c612c78160135df0e9011d5bcd93533d14d4e8`. El stack vigente incorpora PR #45 `bbd12c7d893e9df9a866dc2c20f1e253fc9435ff` y POSC-D de PR #46; esos antecedentes no acreditan ejecución de este demo. El diff propio de #47 contenía exclusivamente este WIP documental. Este incremento #48 implementa demo local; #49 continúa independiente.

## Objetivo y precedencia

El usuario pide priorizar todas las pantallas y acciones del frontend para presentar al cliente, autorizando funcionalidad simulada antes del backend. La aceptación de este corte es experiencia comercial completa con repositorio demo stateful, no homologación ni integración real. No se atribuye al backend ningún resultado del simulador.

Este WIP complementa [UX v1](../20260923-blackstore-frontend-ux-system-v1/1-functional/spec.md), [PROJECT](../../PROJECT.md) y [PATTERNS](../../PATTERNS.md). La instrucción explícita del usuario autoriza una excepción a la antigua prohibición visual de DEMO únicamente bajo `/demo`: identificación discreta y permanente de datos simulados. No cambia las restricciones de las rutas operativas ni activa StoreCore, facturación o proveedores.

Compatibilidad de base: [POSC-D](../20261008-blackstore-pos-cart-explicit-payment/meta.md) conserva su diseño aprobado y B/T/U/E pendientes; [CLR](../20261007-blackstore-cash-ledger-reconciliation-reports/meta.md) conserva lifecycle, journal, contratos contables y sus gates propios. Ningún resultado demo satisface aceptación POSC/CLR ni hereda evidencia del snapshot combinado externo.

La base operativa tiene siete rutas funcionales, pero su ticket individual y pago predeterminado no constituyen un POS comercial terminado. Este corte agrega dashboard, carrito multilínea, cobro explícito, comprobante, caja, ventas, productos, inventario, clientes y reportes con navegación y estados completos. El WIP backend-first de carrito continúa independiente; sus contratos no se reescriben para acomodar fixtures.

## Fuentes del contrato

- [Especificación funcional](1-functional/spec.md)
- [Arquitectura y aislamiento](2-technical/spec.md)
- [Plan de entrega](3-tasks/plan.md)
- [Inventario verificable de acciones](3-tasks/cta-inventory.md)
- [Aceptación](4-acceptance/checklist.md)

Gates locales sobre source `c20d3bc0f00a7bfa9dd40d2c00683fe20e4a64f4`: Domain 51/51 PASS; Angular regression 295/295 PASS; app/spec/demo/DCT typechecks PASS; build PASS; Browser 19/19 PASS. E01 real SID/PostgreSQL metadata NOT_RUN (frontend fixture boundary PASS); E11 domain matrix/browser PASS_LOCAL; Design independiente NOT_RUN; Review independiente RE_REVIEW_PENDING_AFTER_SECURITY_FIXES; E08 manual screen reader/contrast/zoom NOT_RUN; Live integration OUT_OF_SCOPE. Ver [evidencia de límite de restitución](evidence/PR48-reversal-boundary-local-20261020.md). No cerrar ni declarar aceptación completa.

Corrección documental sobre `228f680` (2026-10-20): B debe separar bootstrap y shell antes de SessionStore/health/recovery, ya que providers lazy por sí solos no aíslan el arranque global existente; E01 exige cero API y conservación SID/storage con entrada directa, reload y transición entre superficies. Política demo cerrada por acción y revalidación al ejecutar definida en funcional/técnica, tarea T y E11; no deriva permisos de RBAC operativo. Esos requisitos se implementan en el incremento #48 con los límites de ejecución documentados arriba; la revisión independiente y la aceptación completa siguen pendientes.
