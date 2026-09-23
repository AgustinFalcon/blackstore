# ADR-002 — Durable aggregate stock saga

**Status:** proposed

Una venta/reserva completa tiene una sola `aggregate_operation_key`, no una por línea. Antes de cualquier llamada remota se persisten en una transacción sale intent, venta provisional PENDING_RESERVATION, audit y command outbox RESERVE. La reserva devuelta se guarda con reference, state, receipt, version y expiry. Pago → COMMIT_PENDING/outbox → commit; cancelación/fallo → RELEASE_PENDING/outbox → release. Cada llamada, retry y consulta usa la misma key.

Antes/después de reserve, payment, commit o release, un crash deja comando durable; worker reintenta o consulta operación y registra reconcile. Sin contrato StoreCore aprobado sólo se prueba con puerto/simulador. StoreCore sigue siendo autoridad de stock; BlackStore no vende offline.
