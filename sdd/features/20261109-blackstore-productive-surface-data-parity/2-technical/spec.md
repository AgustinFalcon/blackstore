# Spec técnico — paridad productiva BlackStore

## Arquitectura y límites

- Dominio TypeScript/Kotlin sin framework y con conjuntos finitos cerrados.
- El JSON entra por mappers únicos. Un wire desconocido produce `Unknown` neutral.
- Angular conserva `Container/View → Store/Service → mapper → HTTP` y no interpreta strings wire.
- Kotlin mantiene controladores → aplicación → puertos; PostgreSQL de BlackStore nunca consulta la DB de StoreCore.
- `LocalDemoRepository` permanece bajo bootstrap/rutas Demo y no se inyecta en producción.

## Tipos cerrados nuevos o consolidados

- `SurfaceDataSource`: `Real`, `Fixture`, `Mixed`, `Unavailable`, `Blocked`, `Unknown`.
- `PersistenceReadiness`: `PostgresqlRequired`, `Durable`, `MemoryResettable`, `BlockedStoreCoreLive`, `Unknown`.
- `CatalogSource`: `StoreCoreLive`, `LocalFixture`, `Unavailable`, `Unknown`.
- `CatalogFreshness`: `Current`, `Stale`, `Unavailable`, `Unknown`.

Etiquetas y reglas viven en esos tipos. No se aceptan comparaciones de literales en componentes, stores o tests.

## Contratos por superficie

### Sesión e inicio

- Reusar `/api/v1/auth/*`, `/api/v1/workspace`, `/api/v2/pos/context`.
- El readiness productivo requiere identidad disponible y persistencia durable; no se infiere por la mera salud HTTP.
- No agregar administración de personal en este corte.

### Catálogo

- Mantener GET `/api/v1/catalog`; agregar mapper runtime estricto en frontend.
- Ampliación backward-compatible exacta: se conservan envelope, nullability y todos los campos vigentes (`version`, `importedAt`, `validUntil`, `stale`, `canonicalPath` e `items[{sku,name,variantId,priceVersion,unitPrice,rawSku,rawName,rawVariantId,rawPriceVersion}]`). La única adición HTTP es `source` con wire `STORECORE_LIVE | LOCAL_FIXTURE | UNAVAILABLE | UNKNOWN`, protegida por test de contrato aditivo. El mapper de presentación puede descartar `raw*`, pero el backend no los elimina en este corte.
- Procedencia se traduce sólo desde `source`; frescura sólo desde `stale`/disponibilidad, nunca desde textos de versión.
- El adapter fixture declara `Fixture`; el adapter live permanece detrás de los gates existentes y no se activa.
- No agregar `active`, cantidades, imágenes o categorías ficticias al contrato productivo.
- `Stale`, `Unavailable` y `Unknown` impiden seleccionar/reservar. `LocalFixture + Current` requiere aceptación local explícita y no satisface readiness productivo.

### Ventas/ticket

- Reusar v1/v2/v3 actuales; no crear una cuarta versión.
- Las proyecciones muestran sólo fecha, moneda, total, líneas, pagos y estado que los endpoints actuales ya entregan. Campos ausentes se rotulan `No disponible`.
- List/detail son `no-store`, owner-scoped y permission-scoped.
- Recovery de response-loss continúa GET-only sobre la identidad existente.
- Este WIP no agrega rutas, DTOs ni permisos de ventas. Cualquier lectura adicional requiere otro WIP y GO explícito.

### Caja/reportes

- Reusar contabilidad v2 y reportes shift/day.
- La UI usa exclusivamente las lecturas existentes. No agrega históricos/exportaciones ni nuevas lecturas del ledger; campos faltantes se rotulan `No disponible`.
- Totales usan decimal/ARS explícito y zona IANA validada.
- Este WIP no agrega rutas, DTOs ni permisos de caja/reportes. Una expansión del ledger requiere otro WIP y GO explícito.

## Seguridad

- Cookie opaca, CSRF, same-origin y RBAC existentes se mantienen.
- No recuperar autoridad desde `X-Actor-Id`/`X-Role` del cliente.
- PII y evidencia privada llevan `Cache-Control: no-store`.
- Ninguna ruta Demo comparte sesión, IndexedDB journal ni endpoint operativo.
- No secretos, credenciales o payloads de proveedor en DTOs/pantallas/logs.
- La matriz vigente queda congelada: sólo health, preflight `OPTIONS`, emisión CSRF y login son públicos. Consulta de sesión y logout requieren SID válido; logout además exige CSRF/origen. Workspace/context y lecturas operativas requieren SID válido y permiso actual; comandos de caja/venta requieren permiso de escritura actual; Auditor conserva sólo lectura. No se amplía `StaffSessionFilter` ni se confía en headers de actor/rol.

## Aceptación

1. Tests de manifest: rutas cerradas cubiertas exactamente una vez y ownership coherente.
2. Mapper/unit tests: todos los casos cerrados + malformed/Unknown + no raw wire.
3. Angular: arquitectura, tests, build y Playwright 320/390/768/1440 con axe/reflow.
4. Real: PostgreSQL 16 limpio, Flyway actual, JAR y Angular; en toda superficie productiva que contenga datasets `BLACKSTORE_*`, esas dependencias viajan por HTTP real sin `route.fulfill`, independientemente de la procedencia agregada `Real`, `Mixed` o `Unknown`. Esto incluye sesión, inicio, caja/cierre, ticket, ventas, detalle y reportes; sólo `/demo/**` queda excluida. El fixture StoreCore sólo puede existir dentro del adapter backend.
5. Journey obligatorio: login → abrir caja → catálogo fixture explícito → venta multilínea → registrar pago/commit → listado/detalle → reiniciar backend → recuperar por GET, sin replay de comandos.
6. Negativos: source/freshness `Unknown` bloquean comandos; SID ausente/inválido/revocado falla y un cambio de actor limpia proyecciones privadas; IDOR/cross-owner se deniega; Auditor no escribe; CSRF inválido se deniega; éxito y error privados llevan `no-store`.
7. Reinicio: sesión/caja/venta/reporte reconstruyen lectura durable; Demo se mantiene aislada.
8. Safety: integración StoreCore deshabilitada antes y después; ninguna llamada de red no-loopback.

## Secuencia

Primero congelar matriz y documentación; luego cerrar bordes/readiness/catálogo; después profesionalizar lecturas BlackStore-owned; finalmente ejecutar browser real. StoreCore live, fiscal y proveedores externos quedan en WIPs/release separados.
