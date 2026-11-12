# Progreso de implementación

## Corte local 2026-11-12

- Migración V14 agrega cabecera singleton y proyecciones `LEGACY`/`V2` con sus
  campos raw, constraints, permisos mínimos y reemplazo atómico.
- `CatalogSource` incorpora `STORECORE_CACHE`; backend y frontend traducen el
  wire desconocido a `Unknown` y nunca muestran texto crudo.
- `CatalogSnapshotValidator` sólo admite snapshots live, frescos, no vacíos,
  con vencimiento explícito, identidades únicas y montos válidos.
- `DurableCatalogAdapter` exige coincidencia exacta de path, versión y digest;
  una caída recupera cache explícito y recalcula vigencia.
- `JdbcCatalogSnapshotStorePostgresTest` prueba instalación limpia, reemplazo,
  recreación del store y upgrade V13→V14 sin evidencia inventada.
- Default: `fixture`, `blackstore.catalog-cache.enabled=false`; no se activó
  StoreCore live, tráfico, proveedor, fiscal, deploy ni homologación.

## Gates

- Frontend: manifiesto 10/10, typecheck PASS, 429/429 tests PASS, build PASS.
- Backend enfocado: dominio, adapter, PostgreSQL clean/upgrade y regresión de
  migración PASS.
- Backend completo final: 329 tests PASS en 8 min 03 s, incluida matriz
  PostgreSQL/Testcontainers. Durante el gate se actualizó la expectativa Flyway
  13→14 y se estabilizó un cierre de caja histórico frente a saltos del reloj de
  Docker usando `GREATEST(clock_timestamp(), opened_at)`; ambos casos exactos
  reejecutaron PASS antes de la matriz final.

## Remediación de review

La ronda exacta sobre `dcaf3c9` rechazó el corte. Se corrigieron consistencia de
snapshot, precisión monetaria, tipo cerrado de proyección, fallback contractual
y vencimiento nulo. Los tests enfocados de dominio, adapter, clean/upgrade y
concurrencia PostgreSQL pasan; se repite la matriz completa sobre el nuevo SHA.

El head corregido `9374ba93653637aa8c364e58196161b4997be1fc` pasó la
matriz backend completa (329 tests, 7 min 09 s). Bug/Producto y
Seguridad/Arquitectura GPT-6.1 Sol aprobaron independientemente ese SHA sin
hallazgos P0–P3. Frontend conserva manifiesto 10/10, 429/429 tests y build PASS.

## Gate alojado PR #66

El primer Verify alojado (`43292040305`) dejó verdes backend, frontend y
frontend-browser. DCT detectó una expectativa histórica de Flyway V13 después
de incorporar V14; la corrección usa `EXPECTED_SCHEMA_VERSION = 14` y pasó los
primeros seis escenarios locales reales antes de delegar la matriz completa a
CI para no mantener contenedores repetidos innecesariamente.

La nueva ronda estricta rechazó además dos bordes P2 antes de actualizar el PR:

- el transporte loopback ahora expone un resultado cerrado `Available`,
  `Unavailable`, `Rejected` o `Invalid`; sólo `Unavailable` habilita cache;
- V14 conserva nombres válidos de hasta 250 caracteres, igual que el contrato.

Tests enfocados de adapter, HTTP concreto y PostgreSQL/Testcontainers: PASS.
Typecheck DCT: PASS. La revisión final y el nuevo Verify quedan pendientes del
commit exacto de estas remediaciones.

La re-review de `33176f8` encontró que el nombre raw puede exceder 250 por
whitespace externo aunque su forma normalizada válida mida exactamente 250.
`raw_name` ahora es `TEXT`, preserva la evidencia wire completa y la prueba de
frontera usa 250 caracteres normalizados con padding. Resultado enfocado final:
15 tests, 0 fallos, 0 omitidos; no quedaron contenedores de prueba activos.
