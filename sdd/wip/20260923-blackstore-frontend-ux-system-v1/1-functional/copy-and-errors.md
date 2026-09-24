# Copy, envelope y errores

## Envelope

Toda respuesta JSON no-304: `code`, `data`, `errorCode`, `retryable`, `message`, `traceId`.  
Éxito: `code=200`, `data` presente, `errorCode`/`retryable`/`message` null.  
Error: `data=null`, `errorCode`/`retryable`/`message`/`traceId` no-null.  
La UI ramifica por `errorCode`, nunca por `message`. 304 sin body.

## `errorCode` de mostrador

| errorCode | HTTP | retryable | UI |
|---|---|---|---|
| CAPABILITY_DISABLED / entitlement | 403 | false | Banner + DLG-KILL |
| FORBIDDEN | 403 | false | Rol insuficiente |
| VALIDATION | 400 | false | Inline en campo |
| INSUFFICIENT_STOCK | 409 | false | DLG-STOCK; nueva operation_id |
| CATALOG_VERSION_STALE | 422 | false | DLG-STALE |
| CONFLICT | 409 | true | Re-POST misma cuádruple (cuando exista conector) |
| OPERATION_STATE_CONFLICT | 409 | false | Conservar terminal; GET |
| OPERATION_RETIRED | 410 | false | Nunca re-POST |
| NOT_FOUND | 404 | false | Vacío POS-08 |
| RATE_LIMITED | 429 | true | Retry-After |

Hasta GO del adapter, reserva/commit/release son **simulador local**. Esos códigos se documentan para no divergir del contrato StoreCore.

## Microcopy fija

- Integración StoreCore bloqueada — simulador local
- Catálogo de solo lectura
- Margen desconocido (sin costo validado)
- Esta proyección no es resultado fiscal ni caja libre
- La apertura no se edita
- USER de BlackStore no es CUSTOMER de StoreCore
