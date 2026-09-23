# Especificación técnica — BlackStore POS core

**Status:** `superseded` · **Fecha:** 2026-09-21  

<!-- superseded_by: StoreCore sdd/wip/20260921-storecore-pos-integration-contract-v1 -->

**HISTÓRICO — NO IMPLEMENTAR.** No usar ISSUE/REVERSAL ni GET balances ingest. Ver `SUPERSEDED.md` y el OpenAPI canónico StoreCore. El diagrama `/pos-integration/v1` debajo es evidencia muerta.

## Architecture

```text
Angular POS  →  application (ticket, cash, invoice, alerts)
             →  persistence PostgreSQL BlackStore
             →  adapter StoreCoreInventoryClient (blocked until Sol GO)
                    GET  /pos-integration/v1/catalog
                    GET  /pos-integration/v1/stock/variants/{id}
                    POST /pos-integration/v1/reservations
                    POST .../commit | .../release
                    GET  /pos-integration/v1/operations/{operationId}
                    POST /pos-integration/v1/operations/reconcile
```

Kotlin domain sin Spring/JPA/HTTP. Credencial StoreCore: `storecore.credential_secret_ref` → secret manager / `.env`. Nunca en logs.

## Claves

| Uso | `operation_key` |
|---|---|
| Primer ISSUE | `bs:{ticket_id}:issue` |
| Retry mismo body | idéntica |
| Cambio líneas / oversell | `bs:{ticket_id}:issue:{seq}` seq≥2 |
| Reverso pre-factura | `bs:{ticket_id}:reversal` |

`external_ref` = `blackstore:ticket-{ticket_id}`.

## HTTP → ticket

Ver `storecore-contract.md`. Resumen: 200 `OK`/`DUPLICATE` → `CLOSED`; 409 `NO_STOCK` → popup o espera; 409 `CONFLICT` → retry misma clave; 422 `REVERSAL_NOT_ALLOWED`; 400 invalid; 401/403 fail closed; 5xx retry misma clave.

## Jobs

- Alerta oversell: cada N minutos GET balances; si `available_quantity` cubre `qty_committed` → `CLEARED`.
- Escalamiento: `replenish_by` vencido → `ESCALATED_MANAGER` → `ESCALATED_OWNER`.

## Tests obligatorios

Idempotencia; NO_STOCK todo-o-nada; oversell clave nueva; veto REVERSAL si `invoiced_at` NOT NULL; arqueo; lote = suma.

## Gates

Luna: `3-tasks/tasks.json` tras Sol GO. Flyway/Testcontainers del schema BlackStore. No tocar schema StoreCore.
