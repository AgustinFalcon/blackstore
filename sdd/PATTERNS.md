# Patterns — BlackStore

## Installation

Un comercio, un proceso BlackStore, una PostgreSQL propia. StoreCore es hermano, no tenant.

## Architecture

Kotlin domain sin frameworks. Application: ticket, caja, factura, puerto StoreCore. Adapters: HTTP POS, persistence, cliente StoreCore (bloqueado hasta GO). Angular: consola de mostrador.

## Stock client

Persistir intención + cuádruple + `canonical_path` + `contractVersion` + digest + `request_hash` en outbox **antes** del HTTP. Inbox acepta PENDING (`receipt` null) y deduplica por cuádruple + kind + `response_hash`. Timeout → GET. Matriz 409 StoreCore: stock/stale/validation → nuevo `operation_id`; CONFLICT → misma cuádruple; mismatch/EXPIRED conservan evidencia. Sin `allow_oversell`. Sellable = `GREATEST(0, available_quantity - safety_stock)` y `available_quantity` ya es neta de reserved.

## Commercial vs fiscal

Ticket COMMITTED/CLOSED es venta comercial BlackStore. Factura es otro ciclo. StoreCore no conoce fiscal.

## Compliance

No ocultar ventas, no doble libro, no bypass fiscal.

## Backend threads

Spring already owns the servlet request thread, `@Transactional` JDBC, and `@Scheduled`. Inject `DispatcherProvider` only at the blocking HTTP infrastructure edge (`StoreCoreHttpTransport`). Domain, ticket saga, and workers do not hop threads. Tests swap `TestDispatcherProvider`. There is no Android `Main` and no Mercado Libre outbox dispatcher.

## Real StoreCore adapter (post-GO only)

El dominio no conoce HTTP/framework; application usa puertos y la infraestructura implementa el adaptador StoreCore seleccionado en composition root. El único YAML es StoreCore `1.0.0-draft`, pinneado por SHA-256/version/path. BaseResponse se valida completo y se ramifica sólo por `errorCode`; TLS, bearer de identidad de servicio, los cuatro headers, timeout→GET, inbox/outbox, reconcile read-only, tombstone 410 sin re-POST, redacción y kill switch son obligatorios. Ver `sdd/wip/20260921-storecore-connector-adapter/`.
