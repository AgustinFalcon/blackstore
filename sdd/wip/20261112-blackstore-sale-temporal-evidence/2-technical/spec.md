# Especificación técnica

## Fuente de autoridad

- `sale_intents.created_at` -> `saleCreatedAt`.
- `sale_state_projection.updated_at` -> `saleUpdatedAt`.
- `payments.created_at` -> `payments[].createdAt`.

No se agrega migración: las tres columnas existen desde V1 y son `NOT NULL`.
Las consultas deben usar alias explícitos para evitar colisiones de
`SELECT p.*, i.*`.

## Dominio backend

`StoredSale` incorpora `SaleTemporalEvidence.Known(createdAt, updatedAt)` o
`Unknown` si el reloj persistido retrocedió. La inconsistencia temporal nunca
bloquea lectura, recovery ni mutaciones comerciales. Cada
`PaymentLedgerEntry` incorpora `createdAt`. Los valores son `Instant`; JDBC
traduce una sola vez desde PostgreSQL. El controller sólo serializa ISO-8601.

## Borde frontend

`RecordedInstant` es un value object cerrado con `Known`/`Unknown` mediante
constructor privado y único `fromWire`. Conserva un `Date` válido y expone una
presentación localizada; `Unknown` devuelve `No disponible`. Vista, store y tests
consumen este tipo, no strings ni `Date.parse` dispersos.

`DurableSaleSummary` agrega `createdAt` y `updatedAt`; `DurableSalePayment`
agrega `createdAt`. La validez comercial existente no cambia por timestamps.

## Seguridad y contratos

- No se usan timestamps del cliente para mutaciones, orden o autorización.
- No se infiere fecha de cobro desde `updated_at`.
- No se registra PII nueva.
- JSON inválido se traduce a `Unknown`; no produce excepción ni HTML crudo.

## Pruebas

- Backend unit/controller y PostgreSQL: aliases, ISO, pagos y persistencia tras
  recrear repository.
- Frontend mapper/dominio: válido, null, inválido y coherencia de lista/detalle.
- Componentes: listado, detalle y comprobante ya no contienen texto fijo.
- Gates completos backend/frontend antes de review exact-head.
