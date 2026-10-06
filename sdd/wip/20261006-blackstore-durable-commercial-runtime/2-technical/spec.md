# Especificación técnica — runtime comercial durable

## Evidencia de base

- `LocalSaleSagaService` usa mapas para saga y líneas de reserva; `stored/findSale` leen memoria.
- `JdbcSaleRecordStore` ya admite intención, proyección, líneas, RESERVE outbox y auditoría en una transacción.
- COMMIT/RELEASE, inbox y actualización de proyección usan transacciones separadas.
- Outbox e inbox actuales no conservan todo el payload necesario para replay/aplicación.
- No existe worker comercial: `outbox_delivery_attempts` no se reclama ni recupera.
- El frontend conserva referencia de operación y pago en signals y no descubre ventas tras recarga.

## Tipos de dominio cerrados

```kotlin
sealed interface DurableSaleState {
    data object PendingReservation : DurableSaleState
    data object Reserved : DurableSaleState
    data object PaymentCaptured : DurableSaleState
    data object CommitPending : DurableSaleState
    data object Committed : DurableSaleState
    data object ReleasePending : DurableSaleState
    data object Released : DurableSaleState
    data class ReconciliationRequired(val reason: RecoveryReason) : DurableSaleState
    data object LegacyIncomplete : DurableSaleState
    data class Unknown(val wire: String?) : DurableSaleState
}
```

También se definen `CommandKind`, `DeliveryState`, `RecoveryDisposition`, `RecoveryReason` y `AttemptOutcome` como enum/sealed. Un único mapper JDBC traduce wire↔dominio. `Unknown` nunca habilita dispatch.

## Objetos de flujo

- `AdmitSaleCommand`: valida invariantes y persiste admisión atómica.
- `ClaimPendingCommand`: obtiene un lease con compare-and-set/`SKIP LOCKED`.
- `ResolveUncertainDelivery`: decide GET, espera o reconciliación.
- `DispatchStoredCommand`: envía exclusivamente el payload persistido, fuera de transacción.
- `ApplyRemoteEvidence`: aplica inbox, proyección, intento y auditoría en una transacción.

El orquestador sólo ordena pasos. Cada objeto tiene una razón para cambiar y dominio no importa Spring/JDBC/HTTP/UI.

## Modelo persistente

Migración forward-only V6:

- Amplía los registros históricos inmutables `storecore_outbox_commands` con payload canónico completo, `payload_hash`, actor/caja/motivo; y `storecore_inbox_events` con evidence JSON completo y `evidence_hash`. Identidad, hash y payload siguen sin UPDATE/DELETE.
- Agrega metadata mutable separada: `storecore_command_delivery(command_id, state, claim_token, claim_epoch, lease_until, next_attempt_at, attempts, last_error_code, updated_at)` y `storecore_inbox_applications(inbox_id, command_id, claim_token, state, applied_at, late_reason)`. No se debilitan los triggers históricos.
- Agrega `version` a la proyección de venta para CAS y usa `SELECT ... FOR UPDATE` como lock común de request, worker y payment.
- Índices de claim y unicidad cuádruple+kind.
- Registra filas V1–V5 no replayables en metadata como `LEGACY_INCOMPLETE`; no modifica ni inventa su payload.
- Grants mínimos: worker sólo actualiza metadata; projection/runtime escriben únicamente sus tablas permitidas. Tests prueban que payload/hash/identidad históricos siguen inmutables.

No se borra historia. JSON se versiona con un mapper explícito y checksum SHA-256 del canonical form. La allowlist durable contiene sólo SKU, cantidad, precios/versiones, identidad operacional, actor ID, cash ID, motivo de negocio y referencias remotas redacted; excluye passwords, cookies, CSRF, tokens de proveedor y datos de tarjeta no permitidos.

## Transacciones

1. **Admisión RESERVE:** intención + líneas + proyección + outbox + metadata delivery + auditoría.
2. **Admisión terminal/pago:** lock `FOR UPDATE` de proyección, revalidación de ownership/estado/ledger y escritura atómica. COMMIT/RELEASE agrega outbox+metadata, cambia a pending y audita; pago/reversa agrega ledger/pago y audita bajo el mismo lock. `version` cambia por CAS.
3. **Claim:** cambia metadata/lease con `claim_token` UUID y `claim_epoch` monotónico usando reloj DB; transacción breve.
4. **HTTP:** fuera de transacción.
5. **Aplicación:** inbox inmutable + application metadata + proyección + intento + auditoría; idempotente y condicionado por CAS a command ID + token + estado esperado.

Fallo DB aborta el paso. No existe fallback in-memory si persistencia está habilitada.

## Recovery

- `PENDING`: puede despachar.
- `IN_FLIGHT` con lease vigente: otro worker espera.
- lease vencido tras posible envío: `UNCERTAIN`, exige GET.
- respuesta tardía de un claim vencido: registrar `LATE_IGNORED`; no cambia proyección.

### Matriz cerrada GET → disposición

| Comando | GET remoto | Disposición |
|---|---|---|
| RESERVE | `RESERVED`/`COMMITTED`/`RELEASED` | aplicar evidencia compatible; terminal contradictorio reconcilia |
| RESERVE | NOT_FOUND autoritativo | `RetrySameCommand` con payload/identidad persistidos |
| COMMIT | `COMMITTED` | `ApplyEvidence` |
| COMMIT | `RESERVED` | `RetrySameCommand` COMMIT |
| RELEASE | `RELEASED` | `ApplyEvidence` |
| RELEASE | `RESERVED` | `RetrySameCommand` RELEASE |
| cualquiera | `PENDING`, timeout, 5xx, red indisponible | `WaitAndGet`; nunca POST inmediato |
| cualquiera | `EXPIRED`, mismatch, contrato incompatible, Unknown | `ReconciliationRequired` |
| COMMIT/RELEASE | NOT_FOUND autoritativo | `ReconciliationRequired`; nunca recrea reserva |
| COMMIT | `RELEASED` | `ReconciliationRequired` |
| RELEASE | `COMMITTED` | `ReconciliationRequired` |

Agotar backoff/attempts produce `ReconciliationRequired`, no repost ilimitado. Cada decisión vive en `RecoveryDisposition`/`RecoveryReason` y tiene test exhaustivo.

Backoff y máximo de intentos son configuración acotada. El worker sólo corre cuando integración fixture/loopback y capability lo permiten; live sigue bloqueado.

## API y UI

- `GET /api/v1/sales?cursor=&limit=&state=`: lista durable filtrada por ownership.
- `GET /api/v1/sales/operations/{operationId}`: detalle durable actual.
- `DurableSaleDetail`: cuádruple, caja/actor, líneas/totales, estado, evidencia redacted, pagos/reversas, pending command y `allowedActions` cerradas.
- `allowedActions` se calcula después de RBAC/ownership; venta ajena devuelve 404 para Cashier. Owner ve recovery técnico redacted; Auditor no obtiene ticket comercial.
- Acciones existentes reutilizan la identidad; nunca crean otra al reabrir. El endpoint de detalle es read-only y abrirlo emite cero POST.
- Frontend agrega `DurableSaleState` TS cerrado con `fromWire`, un mapper único y pantalla/lista de operaciones recuperables.
- `OpenExistingSale` hidrata un store distinto de `StartNewSale`; no llama reserve/payment. Pago parcial permite completar explícitamente; pago completo permite la decisión terminal válida; terminal/legacy/Unknown son read-only salvo reconciliación autorizada.
- Respuestas tardías siguen aisladas por generación; no hay retry automático de pagos.

## Observabilidad y datos sensibles

Métricas: pendientes por estado, leases vencidos, GET recovery, reconciliaciones y edad máxima. Logs/audit usan cuádruple/IDs y códigos cerrados; nunca payload sensible, cookies o CSRF. Endpoints técnicos se mantienen privados y redacted.

## Migración y compatibilidad

V6 arranca sobre V5 sin reset. Filas completas nuevas son replayables; filas previas quedan consultables como legacy bloqueado. La migración no cambia gates StoreCore ni activa workers live.

## Pruebas obligatorias

- Dominio/mappers: exhaustividad, Unknown, round-trip y hashes.
- PostgreSQL: rollback admisión, atomicidad aplicación, replay/mismatch, leases, grants.
- Reinicio: cerrar contexto A y abrir B sobre la misma DB para cada estado.
- Fallos: antes HTTP, después aceptación, después inbox, antes respuesta.
- Concurrencia: doble claim, worker vs request, payment vs terminal command.
- Fencing: A vence, B reclama/aplica, A responde tarde y queda `LATE_IGNORED`.
- Browser: reservar/pagar, reiniciar backend, recargar, listar/reabrir y terminar sin identidad nueva.
- Browser read-only: reabrir pago parcial/completo/terminal/legacy/Unknown emite cero reserve/payment por sí solo.
- Arquitectura: dominio sin framework; ninguna autoridad desde headers.
