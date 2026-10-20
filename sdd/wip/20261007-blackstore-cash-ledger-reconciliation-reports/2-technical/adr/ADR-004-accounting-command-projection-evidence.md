# ADR-004 — Evidencia autoritativa de pagos y gastos tras un comando

Estado: proposed; CLR-T08-D documental, revisión/GO específicos pendientes. Fecha: 2026-10-08.
Base backend POS exacta: `fc858de42a5e021aab2f47ab68794a2519ff2cb5`.
Complementa [ADR-002](ADR-002-sale-v2-lifecycle-command-journal.md) y [ADR-003](ADR-003-pos-execution-context.md); no hereda sus aprobaciones.

## Brecha comprobada y alcance

`PaymentLedgerEntry.originalPaymentId` y `PaymentTransitionPolicy` ya conservan/validan referencias, pero `DurableSalePaymentResponse` y su construcción en SaleController omiten ese dato. El browser no puede verificar refund→capture con la lectura durable. ExpenseRecord persiste `expenseId`, `settlementId` y `ledgerEventIds` en el receipt, pero no existe una query de proyección por commandId para acreditar el gasto/devengo/liquidación recuperado. Accepted de saga y Committed contable no sustituyen evidencia de proyección.

Este corte modifica sólo SDD. No crea endpoints, DTOs, tablas, migraciones ni código productivo; implementación, PG16, browser, CI y reviews exact-head quedan pendientes en CLR-T08-D-BACKEND/T08-C/T09. No backfill, reconstrucción desde saldo, activación ni nuevos permisos implícitos.

## Pagos durables y relación refund→capture

Añadir `originalPaymentId: Long?` nullable y explícito a `DurableSalePaymentResponse`, conservando el resto del contrato de lectura y propagando el valor persistido desde PaymentLedgerEntry. CAPTURED requiere null. REFUNDED requiere ID positivo de una captura presente en el mismo snapshot y en la misma cuádruple de venta; ese original debe ser CAPTURED, sin original propio, con medio conocido igual e importe exacto igual. Un original sólo admite una reversión íntegra; no se permite refund→refund, referencia a otra venta, self-reference, duplicación de IDs ni referencias ausentes. La reversión puede ser parcial respecto de una venta split, nunca parcial respecto de su captura.

La política existente del dominio es autoridad de esas reglas; extender su lectura/traductor y pruebas sin duplicar reglas en controller, store o componente. El único traductor HTTP/DB preserva null y trata valor ausente/inválido/desconocido o envelope contradictorio como evidencia incompleta/Unknown, nunca inventa un original por importe, posición, fecha o medio. En historia legacy una referencia faltante no se rellena ni se adopta como v2 válida. Backend y frontend contrastan la relación antes de usar saldo/cobertura/allowedActions; una inconsistencia bloquea captura, commit, release y cierre dependientes de esa evidencia. No mutar el estado persistido para presentar Unknown.

## Query de proyección de gastos

Proponer `GET /api/v2/expenses/commands/{commandId}/projection`, UUID válido, SID actual, BaseResponse y `Cache-Control: no-store` para éxitos y fallos. No admite actor/rol/ownership desde headers/payload, no reclama comando, no hace POST ni HTTP StoreCore y no escribe hechos ni recibos. Está disponible en PreActivation/Active/Paused para recuperación histórica autorizada; su lectura no habilita una nueva mutación.

Dominio sin Spring/JDBC/HTTP: `ExpenseCommandProjectionResult` sellado con `Found(projection)`, `NotFound`, `Unavailable`, `Unknown`. `ExpenseProjectionOperation` cerrado: Accrue, AccrueAndSettle, SettleExisting, Unknown, reutilizando/extendiendo el vocabulario de ExpenseInstruction en un solo borde. Kotlin sealed/enum; TypeScript clase con constructor privado, instancias/fábricas cerradas y único fromWire en PosWireMapper. Etiquetas, completitud y reglas de desbloqueo viven en el tipo. Unknown nunca muestra texto crudo ni habilita acción.

Envelope discriminado por `state`: Found lleva `projection` no null; NotFound/Unavailable/Unknown llevan null. HTTP 200 Found; 404 opaco NotFound para ausente/no visible/kind distinto, sin distinguirlos; 503 Unavailable para fuente insuficiente o DB indisponible después de la autoridad observable; UUID inválido 400, sesión ausente 401 y permiso de ruta ausente 403. Unknown es el resultado neutral del traductor ante schema/state desconocido o envelope contradictorio, no una excepción ni un éxito ficticio. Los fallos HTTP se traducen a tipos cerrados y mensajes seguros antes del store.

Found contiene:

- `commandId`, kind EXPENSE_RECORD conocido, `operation`, `cashSessionId`, `expenseId`, `settlementId` nullable y `ledgerEventIds` del comando, correlacionados exactamente con su receipt durable autorizado.
- `expense`: ID/caja/categoría, amount exacto positivo, actor original y createdAt del devengo acreditado. Un devengo impago no tiene medio de pago acreditado: paymentMethod de liquidación es null, no convertir OTHER histórico en efectivo pagado.
- `settlement`: null para Accrue; para AccrueAndSettle/SettleExisting incluye ID/gasto/caja, amount total exacto, medio conocido, actor, commandId y paidAt. SettleExisting referencia gasto previo íntegro; no cambia `expenses.paid_at`, ni soporta pago parcial/multimedio. La proyección está fijada al comando consultado: una liquidación posterior no transforma la proyección de un Accrue anterior en pagado.
- `accountingEvidence`: committedAt del receipt, postings del comando con ID/kind/componente/medio/importe firmado/origen/occurredAt, `accountingVersion`, completitud cerrada/causas y `snapshot` con cutoff/asOf e identificador de la lectura consistente. Las referencias históricas de un SettleExisting al devengo previo se validan en ese snapshot sin atribuir sus postings al comando nuevo.

Found acredita cobertura completa del comando, no del período DAY, del lifecycle, de la venta ni de toda la caja. Accrue exige un EXPENSE_ACCRUAL sin settlement/EXPENSE_PAID; AccrueAndSettle exige devengo y única liquidación con EXPENSE_PAID; SettleExisting exige la liquidación total y EXPENSE_PAID del nuevo comando más devengo original coherente. Receipt, refs, importes, medios, actores, caja, orígenes y conjunto exacto de IDs deben concordar; datos faltantes/Unknown/duplicados/fan-out/contradicción producen Unavailable, jamás Found con cero o evidencia sintetizada. Un receipt de kind diferente no se interpreta como gasto.

## Autoridad, snapshot y responsabilidades

Clasificar la ruta explícitamente en StaffSessionFilter con AccountingCommandRead existente. Luego revalidar actor ACTIVE, permiso actual ExpenseRecord del kind original y visibilidad/ownership de caja dentro de la misma lectura, antes de devolver existencia, receipt, mismatch, completitud o estado del lifecycle. CASHIER sólo caja propia; SUPERVISOR/OWNER según la política actual, sin nueva concesión a AUDITOR/Unknown. Una caja cerrada no impide consulta/replay de un comando histórico autorizado: separar permiso de lectura histórica de admisión de nuevo ExpenseRecord (que exige OPEN). Revocación actual bloquea aunque el actor sea quien emitió el comando. Actor original es evidencia, nunca autoridad del lector.

Un puerto application de query y pasos independientes de autoridad, carga de receipt/fuentes, validación de evidencia y construcción del resultado; un adaptador JDBC usa una conexión PostgreSQL REPEATABLE READ read-only para autorización, caja, receipt, gasto, settlement y postings. Revalidar autoridad actual al iniciar ese snapshot; no cachear SID/rol/ownership ni mezclar lecturas de transacciones distintas. No prometer revocación retroactiva después del snapshot. Sin locks de writers ni joins que multipliquen componentes, sin MAX(identity) como orden de commit. DTO/traductor único en el borde; el dominio decide coherencia y el store sólo ordena recuperación/refresh.

En UI, el receipt Committed debe concordar con la proyección Found del mismo commandId/actor/ámbito/generación; descartar respuestas tardías y no adoptar evidencia de otro actor/caja. NotFound/Unavailable/Unknown preservan journal y bloqueo; ningún resultado provoca POST automático ni generación de otro commandId. Found permite confirmar ese paso únicamente tras evidencia correlacionada y refresh autoritativo requerido por ADR-002; no autoriza siguiente mutación con permisos/lifecycle viejos. Una venta terminal y un cierre siguen sus propias políticas/evidencias.

## Aceptación y DAG

- Tipos/traductores: original null de captura, refund válido de split y total; reference missing/cross-sale/self/refund→refund/duplicate/method/amount mismatch; Unknown neutral y bloqueo sin strings en view/store/tests.
- HTTP/SID: estados/envelopes conocidos y desconocidos, UUID inválido, 401/403/404 opaco/503, kind no gasto, caja propia/ajena/inexistente/cerrada, actor inactivo/revocado, AUDITOR/Unknown denegados y no-store. GET no escribe ni dispara HTTP externo; PreActivation/Paused preservan sólo lectura autorizada.
- PG16 real: Accrue, AccrueAndSettle y SettleExisting, originales intactos, grants mínimos sin nueva escritura, receipt/fuentes coherentes, corruptos/ausentes y legacy sin backfill, fan-out, snapshot frente a liquidación concurrente en ambos órdenes con barreras. Cutoff/asOf y conjunto exacto de hechos acreditados; una liquidación posterior no cambia evidencia del comando de devengo.
- Recovery/browser T08-C/T09: pérdida de respuesta/reload/reinicio con mismo commandId, receipt más Found, actor/SID/tab/generación, NotFound/Unavailable/Unknown bloqueados y cero POST automático. Artefactos sanitizados incluyen source/build/PG major/migraciones, snapshots/IDs/resultados y teardown; PG18/MockMvc/fixture/CI no sustituyen PG16/browser.

Orden vigente: B-POS exacto → CLR-T08-D SDD/review/GO específicos → CLR-T08-D-BACKEND implementación/reviews/CI → T08-C → T09. B-POS conserva sus gates pendientes, C queda bloqueado también por evidencia, T08 parcial y T09 planned. Ninguna tarea se marca done por este documento. Gates externos StoreCore/fiscal/companion/homologación/publicación permanecen bloqueados; sin push/PR/deploy ni /sdd.finish en este corte.
