# Especificación técnica

## Diseño

- `CatalogSource` agrega el caso cerrado `STORECORE_CACHE`.
- `CatalogSnapshotValidator` valida contrato, versión, timestamps, unicidad y
  campos de ítems antes de persistir.
- `CatalogSnapshotStore` es un puerto de salida del dominio/aplicación.
- `JdbcCatalogSnapshotStore` persiste una cabecera singleton y sus ítems en una
  transacción PostgreSQL. El JSON/wire nunca entra directo al store.
- `DurableCatalogAdapter` decora al adapter loopback: intenta remoto; si recibe
  evidencia válida la guarda y la retorna; en ausencia retorna el último cache
  con `source=STORECORE_CACHE` y `stale` recalculado.
- La configuración loopback compone explícitamente transporte, store y
  decorador. El modo fixture no cambia.

## Persistencia

`catalog_snapshot_cache` contiene una única instalación/snapshot por versión
actual. `catalog_snapshot_items` conserva separadamente las proyecciones
`LEGACY` y `V2`, incluidos los campos raw exactos, y se reemplaza dentro de la
misma transacción.
No contiene credenciales, PII ni datos fiscales. Las constraints limitan fuente,
campos no vacíos y montos no negativos.

## Seguridad y fallos

- No fallback silencioso desde contrato incompatible.
- No escritura si `CatalogSource` no es live.
- SQL parametrizado, transacción atómica y rol existente `blackstore_app`.
- Error de persistencia no convierte una respuesta remota válida en cache falso;
  la lectura live puede continuar, pero el fallo queda observable en tests/log.
- Cache vencido continúa legible para diagnóstico y queda bloqueado para venta
  por la política existente de catálogo.

## Gates

Backend unit/integration PostgreSQL, frontend unit, build, arquitectura y Verify
completo. Revisión Bug/Producto y Seguridad/Arquitectura sobre el SHA exacto.
