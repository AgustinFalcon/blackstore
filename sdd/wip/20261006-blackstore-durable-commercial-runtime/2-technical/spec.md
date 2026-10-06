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

- Amplía `storecore_outbox_commands` con payload canónico completo, `payload_hash`, actor/caja/motivo, `delivery_state`, lease owner/until, next attempt y last error redacted.
- Amplía `storecore_inbox_events` con evidence JSON completo, `evidence_hash` y estado de aplicación.
- Índices de claim y unicidad cuádruple+kind.
- Marca filas V1–V5 no replayables como `LEGACY_INCOMPLETE`; no inventa payload.
- Grants mínimos separados para runtime, projection y worker.

No se borra historia. JSON se versiona con un mapper explícito y checksum SHA-256 del canonical form.

## Transacciones

1. **Admisión:** intención + líneas + proyección + outbox + auditoría.
2. **Claim:** cambia estado/lease de una fila elegible; transacción breve.
3. **HTTP:** fuera de transacción.
4. **Aplicación:** inbox completo + proyección + intento + auditoría; idempotente por hashes/keys.

Fallo DB aborta el paso. No existe fallback in-memory si persistencia está habilitada.

## Recovery

- `PENDING`: puede despachar.
- `IN_FLIGHT` con lease vigente: otro worker espera.
- lease vencido tras posible envío: `UNCERTAIN`, exige GET.
- GET confirma resultado: aplicar evidencia.
- GET sin resultado explícito y política segura: reintento con misma identidad/payload.
- mismatch/Unknown/contrato incompatible/tombstone: `RECONCILIATION_REQUIRED`.

Backoff y máximo de intentos son configuración acotada. El worker sólo corre cuando integración fixture/loopback y capability lo permiten; live sigue bloqueado.

## API y UI

- `GET /api/v1/sales?cursor=&limit=&state=`: lista durable filtrada por ownership.
- `GET /api/v1/sales/operations/{operationId}`: detalle durable actual.
- Acciones existentes reutilizan la identidad; nunca crean otra al reabrir.
- Frontend agrega `DurableSaleState` TS cerrado con `fromWire`, un mapper único y pantalla/lista de operaciones recuperables.
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
- Browser: reservar/pagar, reiniciar backend, recargar, listar/reabrir y terminar sin identidad nueva.
- Arquitectura: dominio sin framework; ninguna autoridad desde headers.
