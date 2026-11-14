# Rutas operativas de detalle BlackStore

- **Feature:** `blackstore-operational-detail-routes`
- **Base:** `master` `822c431127fe8b979d46cff9d8bec38edd0d4a37` (PR #56 POSC-E)
- **Estado:** `integrated_superseded_by_productive_surface_parity`
- **Alcance:** frontend operativo, contratos HTTP de lectura ya existentes y documentación SDD.
- **Fuera de alcance:** endpoints nuevos, facturación fiscal, StoreCore live, Mercado Pago, homologación, demo comercial y cambios de persistencia.

El snapshot histórico `115fad53006b55d27b021883bf49153a3de050fa` se usa sólo como fuente de diseño. No se integra ni se cherry-pickea porque mezcla 122 archivos de POSC/V3/demo anteriores al master vigente.

Reconciliación 2026-11-14: PR #57 integró ODR en `master 7382616`; PR #61
incorporó su aceptación acumulada dentro de la paridad de superficies
productivas. Este paquete conserva trazabilidad histórica y ya no representa
trabajo runtime abierto.
