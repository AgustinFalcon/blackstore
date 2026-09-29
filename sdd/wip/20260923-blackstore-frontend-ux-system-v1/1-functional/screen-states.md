# Estados por pantalla

Toda ruta usa los seis estados globales. Copy en español rioplatense. Sin datos inventados: vacío ≠ fixture de demo.

| ID | Default | Loading | Vacío | Error + Reintentar | Disabled | Éxito |
|---|---|---|---|---|---|---|
| POS-01 | Health + banner StoreCore bloqueada + atajos | Skeleton health | Sin sesión: CTA a POS-06 | Backend :8081 caído | Atajo ticket si no hay caja OPEN | — |
| POS-02 | Form abrir o sesión OPEN | POST abrir/cerrar/gasto | Sin sesión en terminal | 409 una sesión por terminal | Cerrar/gasto si no CASHIER de esa sesión | Sesión OPEN / CLOSED auditado |
| POS-03 | Tabla fixture + version/validUntil | GET /catalog | Sin SKUs en proyección | Catálogo no disponible | Venta nueva si stale/offline | Vigente |
| POS-04 | Ticket editable + split | Reserva simulador | Sin líneas | INSUFFICIENT_STOCK / VALIDATION | Commit si no RESERVED; todo si StoreCore blocked live | RESERVED / COMMITTED / RELEASED local |
| POS-05 | Turno + día | GET reports | Sin movimientos del período | Reporte no disponible | Margen oculto (UNKNOWN) | Cifras de ledger |
| POS-06 | Elige USER y rol; manda X-Actor-Id y X-Role. Sin login HTTP. | — | — | — | Submit incompleto | Redirect inicio |
| POS-07 | Arqueo draft | POST cierre | Sin movimientos | Diferencia sin razón | Cierre si ya CLOSED | Cierre append-only |
| POS-08 | Snapshot + cuádruple | GET sale | saleId desconocido | 404 | Acciones mutantes | Estado saga local |

## Banners persistentes (todas)

1. Entitlement ≠ ENABLED → writes disabled.
2. `storeCoreIntegrationEnabled=false` → “Integración StoreCore bloqueada — simulador local”.
3. Catálogo stale → “Catálogo vencido. No inicies una venta nueva.”
4. El estado fiscal no impide cargar ni confirmar la venta. La facturación queda para una pantalla posterior.
