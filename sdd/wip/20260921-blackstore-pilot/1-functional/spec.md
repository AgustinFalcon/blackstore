# Functional Spec — BlackStore pilot

**Status:** `ready_for_sol_review`

## Scope

Roles: CASHIER, SUPERVISOR, OWNER y AUDITOR. Terminales y sesiones de caja múltiples pertenecen a un único companion/StoreCore installation. El piloto modela catálogo read-only, venta online, sesiones/cierres, split payments, gastos, audit/reconciliación y reportes shift/daily. La aprobación del contrato StoreCore es prerequisito externo: antes de ella sólo existen puerto/fixtures, no venta integrada real.

## Acceptance criteria

- AC-1: entitlement ENABLED y service identity ligada a la instalación son requisito de startup/write; una DB tiene exactamente un companion installation.
- AC-2: CASHIER opera sólo su sesión; SUPERVISOR excepciones, OWNER configuración/reportes y AUDITOR evidencia inmutable.
- AC-3: catálogo conserva versión/timestamp/freshness y offline es read-only; StoreCore unavailable bloquea venta real.
- AC-4: una venta/reserva persiste la cuádruple canónica (`client_instance_id`, `device_id`, `sale_id`, `operation_id`), `canonical_path=/blackstore-integration/v1`, `contractVersion`, digest OpenAPI y `request_hash` en outbox **por comando**. `aggregate_operation_key` es derivada/enforced `= operation_id`. Intent+outbox antes de RESERVE. Inbox guarda `state` + `receipt`/`reservation_ref` nullable (PENDING) y deduplica por cuádruple + `operation_kind` + `response_hash`. El cliente StoreCore unwrappea el envelope completo `BaseResponse`: HTTP status=`code`; todo JSON no-304 exige `code,data,errorCode,retryable,message,traceId`; éxito `code=200` con `data` y `errorCode`/`retryable`/`message` null explícitos; error `data=null` con `errorCode`/`retryable`/`message`/`traceId` no-null; ramifica por `errorCode`, nunca por `message`. Al pasar a RESERVED|PAYMENT_CAPTURED|COMMIT_PENDING|COMMITTED|RELEASE_PENDING|RELEASED la proyección exige la tupla remota completa receipt, ref, contract_version, openapi_digest y accepted_price_versions (min 1). RECONCILIATION_REQUIRED exige reconciliation_reason y la misma evidencia remota es atómica: toda la tupla null o toda poblada (accepted_price_versions no vacía). GET 410 OPERATION_RETIRED (`retryable=false`) → nunca re-POST; tombstone o unknown de reconcile no autorizan re-POST.
- AC-5: ticket conserva snapshots; split payments separan collected y fees. Toda reversa/ajuste referencia original, actor, razón y evidencia; no se borra/edita venta/caja.
- AC-6: StoreCore es autoridad de inventario y BlackStore de venta/caja; comunicación sólo por contrato versionado aprobado, nunca DB directa.
- AC-7: reportes separan gross sales, discounts, net sales, refunds, collected, fees/gastos y cash flow. Margen requiere costo validado; proyecciones no son fiscal/free cash.
- AC-8: cada venta presencial persiste en test y en producción. El estado fiscal no impide cargarla ni confirmarla. No hay emisión en este piloto. Nunca ocultamiento ni doble libro. La facturación por rango de fechas es una pantalla posterior. Las ventas online siguen en StoreCore.

## Deferred

Conector real StoreCore es el ítem bloqueado `DEFERRED-STORECORE-CONNECTOR-001`; fiscal adapter externo, agregación mensual legal, multi-branch, stock allocation/offline sales y proveedores futuros.
