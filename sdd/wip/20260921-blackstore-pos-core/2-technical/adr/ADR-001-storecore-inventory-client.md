# ADR-001 — Cliente de inventario StoreCore

**Estado:** propuesto / pendiente GO contrato v1 · **Fecha:** 2026-09-21

## Contexto

BlackStore necesita bajar unidades del pool WEB/ML sin registrar una venta StoreCore.

## Decisión

Cliente HTTP síncrono contra el OpenAPI canónico `storecore-pos-integration-contract-v1` (reserve/commit/release/get/reconcile). Intención durable + outbox primero. Timeout → GET con la misma cuádruple. GET `/stock/variants/{id}` para saldos. ISSUE/REVERSAL superseded. Hasta Sol GO: solo puerto/DTO/fixture.

## Consecuencias

Acoplado al contrato versionado de StoreCore. Si StoreCore está caído: catálogo cacheado read-only y **bloqueo** de ventas nuevas (ADR-004 StoreCore). No hay venta ciega de stock. Safety stock no se perfora.

## Rechazado

- Acceso JDBC a PostgreSQL StoreCore.
- ISSUE/REVERSAL como conector real.
- Oversell silencioso.
