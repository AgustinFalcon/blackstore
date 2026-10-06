# Especificación funcional — runtime comercial durable

## Problema

Una venta puede dejar intención, proyección, pagos y outbox en PostgreSQL, pero la API y varias decisiones de recovery dependen de mapas del proceso. Después de reiniciar BlackStore, una operación persistida puede dejar de encontrarse o continuar con datos reconstruidos desde el catálogo actual. Eso impide considerar completa una demo E2E o una homologación.

## Objetivo

PostgreSQL es la única autoridad comercial cuando `blackstore.persistence.enabled=true`. Una venta admitida conserva identidad, caja, actor, líneas, precio/versiones, pagos, comandos, evidencia remota y restricciones después de reiniciar. Un browser nuevo puede encontrarla y reabrirla si su staff tiene permiso y ownership.

## Casos funcionales

### DCR-001 — Admisión durable

Reservar crea, en una transacción local, la intención, proyección inicial, líneas canónicas, auditoría y comando RESERVE completo. Sólo después del commit local puede despacharse HTTP. La misma cuádruple y el mismo comando son idempotentes; la misma identidad con contenido distinto falla cerrado.

COMMIT, RELEASE y captura/reversa de pago comparten un lock durable por venta. Bajo ese lock se revalidan sesión autorizada, ownership, estado y ledger. Admitir COMMIT/RELEASE persiste en una sola transacción el comando inmutable, la transición a estado pendiente y la auditoría. Ningún worker reclama un comando cuya transición local no quedó admitida. La exclusión mutua no depende de un lock del proceso.

### DCR-002 — Comando replayable

Cada comando conserva kind, cuádruple, path, versión/digest, actor autorizado, caja, motivo y payload canónico exacto. Nunca se reconstruye con el catálogo actual. Un registro histórico incompleto queda visible como `LegacyIncomplete` y no se reenvía.

### DCR-003 — Aplicación durable de evidencia

Inbox completo, proyección, intento y auditoría se aplican atómicamente. Una caída antes del commit reintenta sin duplicar; una caída después del commit devuelve el resultado durable. Receipt, reservationRef, versiones aceptadas, expiración y error tipado forman parte de la evidencia.

Cada claim obtiene un `claimToken`/epoch único. Aplicar o finalizar exige compare-and-set del command ID, token y estado esperado. Una respuesta tardía cuyo lease ya fue reemplazado se conserva como evidencia tardía redacted, pero no cambia proyección ni estado de delivery.

### DCR-004 — Recovery por pasos

El recovery se compone de objetos con una responsabilidad: admitir, reclamar, resolver entrega incierta, despachar y aplicar evidencia. Un claim usa lease acotado y backoff. Un lease vencido después de posible HTTP se resuelve con GET de la misma cuádruple antes de cualquier reenvío.

La disposición de recovery es cerrada: `ApplyEvidence`, `RetrySameCommand`, `WaitAndGet`, `ReconciliationRequired` o `Unknown`. No existe una decisión genérica “segura”. RESERVE sólo se reenvía ante NOT_FOUND autoritativo; COMMIT sólo ante estado remoto `RESERVED`; RELEASE sólo ante `RESERVED`. `PENDING`, timeout o indisponibilidad esperan y repiten GET. Estado terminal compatible se aplica; terminal contradictorio, `EXPIRED`, mismatch, NOT_FOUND terminal o Unknown requieren reconciliación.

### DCR-005 — Estados cerrados

`CommandKind`, `DeliveryState`, `RecoveryDisposition` y `DurableSaleState` son tipos cerrados. Valores desconocidos traducen a `Unknown`, conservan evidencia y bloquean mutaciones. La vista, store y tests usan esos tipos; no strings mágicos.

### DCR-006 — Consulta y reapertura

La API expone lista paginada y detalle durable de operaciones visibles. Cashier sólo ve ventas de sus cajas; Supervisor/Owner ven todas las cajas locales; Auditor sólo recibe endpoints expresamente habilitados. Reabrir usa identidad existente y nunca genera cuádruple nueva.

El detalle incluye identidad/cuádruple, caja y actor, líneas y totales canónicos, estado cerrado, evidencia redacted, historial de pagos/reversas, comando pendiente y `allowedActions` calculadas por dominio. La UI tiene una entrada separada “reabrir”; hidrata un contexto existente y emite cero POST por el solo hecho de abrir. Pago parcial, pago completo, terminal, legacy y Unknown tienen vistas y acciones explícitas.

### DCR-007 — Pago y decisión terminal

Tras reinicio, los pagos persistidos siguen vinculados al ticket. Nunca se captura un segundo pago por recovery automático. Commit/release conserva actor y motivo autorizados; una sesión browser nueva puede ejecutar el paso permitido sin sustituir el actor histórico.

### DCR-008 — Degradación fail-closed

DB no disponible, evidencia incompleta, contrato incompatible, mismatch, tombstone, `EXPIRED` o `Unknown` no activan fallback a memoria ni HTTP posterior. La UI muestra un estado cerrado y una acción segura: reintentar consulta, reconciliar o bloquear.

## Matriz RBAC/ownership

| Acción | Cashier | Supervisor/Owner | Auditor |
|---|---|---|---|
| Listar/reabrir venta | sólo caja propia | cualquier caja local | no, salvo reporte separado |
| Reanudar RESERVE | propia | visible con motivo si ajena | no |
| COMMIT/RELEASE | propia según política actual | visible; motivo si ajena | no |
| Ver recovery técnico | no | Owner | lectura redacted |

La autoridad proviene exclusivamente de la sesión staff y asociaciones persistidas. Headers/payload no aportan identidad.

## Criterios de aceptación

1. Reiniciar con ventas `RESERVED`, `COMMIT_PENDING`, `RELEASE_PENDING` y terminales conserva ticket, pagos, actor, ownership y restricciones.
2. Crash en cada frontera transaccional no produce pérdida silenciosa ni transición parcial irrecuperable.
3. Replay idéntico devuelve el mismo resultado; identidad igual con payload diferente falla cerrado.
4. Aceptación remota seguida de caída local se resuelve por GET sin duplicar venta, reserva, cobro o identidad.
5. Ningún comando sale antes del commit local ni mantiene transacción DB durante HTTP.
6. Browser nuevo lista y reabre sólo operaciones autorizadas y conserva su identidad original.
7. Histórico incompleto y valores desconocidos quedan visibles pero no replayables.
8. Todos los gates live permanecen cerrados; fixture/loopback no se presenta como homologación.
9. Claims vencidos y respuestas tardías no pueden sobreescribir una decisión aplicada por un claim posterior.
10. COMMIT, RELEASE y pago compiten mediante lock/CAS PostgreSQL; no admiten decisiones incompatibles.
