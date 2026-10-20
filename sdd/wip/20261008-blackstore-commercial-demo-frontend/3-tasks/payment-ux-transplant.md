# Manifiesto de trasplante PR49

Padre obligatorio: `f9c557b6ec21ef0216dbd4e4e3b926c5ee4ac5f1` (PR48 final).
Fuente histórica: `f52dac4..5e18043`.
Backup local: `backup/pr49-old-20261020` apunta a `5e18043`.
Source dirty de feat/demo-payment-ux preservado; no push.

Se trasplantaron hunks propios de seis commits:

1. `1b23781`: lifecycle simulado, QR/mixto, recibo, terminal y assets locales.
2. `b6cd840`: declaración de caja en ARS, conservando semilla por turno de PR48.
3. `cb0eb8d`: separación del contenido del carrito y filtros compactos.
4. `b0a521c`: predicado stock bajo, navegación y pruebas CTA.
5. `8529c13`: skip-link conserva filtros y mueve foco.
6. `5e18043`: cantidades calculadas desde estado vigente entre renders.

No se copió el snapshot completo, backend, DCT/accounting/POSV3 dirty, rutas
operativas, estilos globales ni cambios de bootstrap. Se preservaron los tipos
ProductStatus, DemoAction/policy, DemoWireMapper, DemoCalendar, DemoQueries,
StockRestorationPlan y el servidor de tests seguro de PR48.
No se trasladaron afirmaciones históricas de aceptación de 863c9f0.

Adaptaciones obligatorias: descuento tras downgrade deniega aprobación;
reversa sin efectivo o con overflow conserva atomicidad; fingerprint incompatibles
deniegan; Cajero conserva permisos de caja; transición vive en PaymentStatus;
reportes por medio usan la partición de efectivo/saldo.

Cobertura CTA añadida en e2e/demo/cta-coverage.spec.ts: cantidades rápidas,
skip-link/query/foco, navegación shell/dashboard, identidades de filas,
empty/recovery, formularios y modales, permisos, bajo stock, categorías,
carrito/descuento, filtros/exportaciones y escenarios. payment-scenarios.spec.ts
cubre terminales, decisiones, retry/cancel, teclado y downgrade pendiente,
mixto móvil, stock/caja/reportes, ilustraciones y recorrido de un producto nuevo.
