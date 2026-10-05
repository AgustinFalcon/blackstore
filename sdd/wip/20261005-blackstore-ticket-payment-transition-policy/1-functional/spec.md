# Especificación funcional

## Problema y resultado requerido

La UI actual dispara capture desde cualquier respuesta HTTP de reserva y puede disparar el segundo pago sin comprobar el estado del primero. El backend captura sin evaluar la venta y su saldo; commit/release deben usar la evidencia de pagos de la misma operación. Este corte debe denegar cualquier escritura cuya elegibilidad no pueda demostrarse localmente.

## Requisitos y aceptación

- **TP-001 — Estados y decisiones cerrados.** Estado de venta, pago, medio, acción, cobertura y motivo de denegación son tipos cerrados. Wire/DB desconocido, vacío, ausente o malformado se convierte a `Unknown`/`InvalidUnknown`; deniega escrituras y tiene etiqueta neutral. Ninguna vista/store/test de dominio compara strings wire ni muestra el valor crudo como válido. El traductor de borde es el único que conoce literales externos.
- **TP-002 — Reserva y captura elegibles.** Sólo una reserva mapeada `Reserved`, de la operación esperada, con envelope e identidad/evidencia válidos, permite avanzar a capture. Backend exige venta existente y elegible, líneas con total positivo, ledger conocido, importe positivo y no mayor al saldo pendiente. Fees se registran aparte y no cubren el ticket. Pending, reconciliación, unknown, retired o bloqueada deniegan; una respuesta HTTP exitosa no demuestra elegibilidad.
- **TP-003 — Split secuencial y pasos.** `ReserveTicketStep`, `CapturePaymentStep` y `RefreshTicketStep` tienen una responsabilidad; el recorrido los ordena. Un segundo capture ocurre únicamente tras un primer `Captured` válido de la misma identidad y con importe/fee exactamente iguales al intento. Error, missing, pending, unknown o envelope inválido detienen el recorrido. El doble submit se bloquea antes de generar cualquier UUID nuevo, además de impedir acciones simultáneas por operación; cambiar/agregar un paso no reescribe los anteriores.
- **TP-004 — Commit con cobertura exacta.** Commit nuevo exige venta elegible, evidencia válida y `Paid`: suma capturada válida de esa operación igual al total del ticket. `Reserved` sin pagos no basta. Totales inferiores, superiores, desconocidos o con historial incompatible deniegan; pagos de otra venta no cuentan. Los estados pendientes/terminales/reconciliación/retired/bloqueados/unknown no generan un comando nuevo. Un replay terminal compatible puede devolver el existente sin nuevo comando ni efecto.
- **TP-005 — Release sin pagos.** Release nuevo exige reserva elegible y ausencia total de historial de pagos de esa operación. Pago parcial, completo, pending, unknown, void o refund/reversa bloquean, incluso si el neto es cero. Este corte no incorpora compensación de pagos. Un replay terminal compatible no crea otro comando.
- **TP-006 — Evidencia por identidad y gates.** UI habilita según decisiones tipadas y backend las reevalúa bajo coordinación local compartida que abarca lectura→decisión→efecto de capture, commit, release y reverse. Venta y ledger usan puertos separados con cuádruple completa e identidad de venta inequívoca; `operationId` solo no autoriza. Cero o múltiples asociaciones deniegan. Antes de una reversa se prueba que el pago original pertenece a esa identidad. La cobertura nunca usa un total global. La matriz negativa/concurrente y disabled/fixture/kill switch/canary 0 deben acreditarse antes de afirmar el corte implementado.

## Precisiones vinculantes tras revisión

TP-001/TP-004/TP-005 distinguen decisiones cerradas `NewCommand`, `RecoverExistingCommand`, `TerminalReplay` y denegación. Pending bloquea comandos nuevos, pero conserva recovery del comando original: GET y, cuando corresponda, CONFLICT→GET→same-body rePOST con su identidad/evidencia y sin duplicar outbox. No habilita captura ni libera por inferencia.

TP-002 exige moneda compatible con `NUMERIC(14,2)`: rango absoluto máximo 999999999999.99, hasta dos decimales efectivos; ceros adicionales exactos pueden normalizarse. Se rechazan subcentavos, overflow y redondeo implícito tanto en memoria como JDBC. `18.001`, `0.001` y cada pago de `10.005 + 7.995` son inválidos aunque una suma pudiera parecer válida.

TP-002/TP-003 requieren contrato BlackStore `PaymentResponse` con `operationId`, `paymentId`, `status`, `amount`, `feeAmount`; identificación de venta y cuádruple completas acompañan la correlación. Reserva/snapshot exponen la identidad, evidencia y total/cobertura necesarios para decisiones verificables. La UI conserva un contexto de intento inmutable y refresca antes de acciones posteriores; respuestas tardías no cambian otro intento.

TP-006 requiere pruebas de barreras para capture/capture, capture/release y reverse/commit. La garantía de serialización es local al proceso compartido; no se afirma exclusión entre instancias ni durabilidad.

## Ejemplo de aceptación

Con ticket total 18, capture 10 deja saldo 8 y cobertura `Partial`; capture posterior 8 deja `Paid`. Capture 9 tras 10 se rechaza antes de guardar. Commit con cobertura 0, 10 o 19 se rechaza; sólo 18 permite un comando nuevo. Release sin historial permite; historial parcial, completo o reversado deniega.

## Límites

Una instalación sigue siendo un comercio y PostgreSQL propia. StoreCore conserva catálogo, precios y stock; este corte no cambia su contrato ni copia tickets. El bloqueo de UI no sustituye el control backend. No se promete persistencia de la saga entre reinicios, serialización entre procesos, transacción distribuida, idempotencia durable de capture, recovery, reconcile, corrección de reportes ni homologación comercial/fiscal.
