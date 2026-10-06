# Especificación técnica

## Dominio y responsabilidades (TP-001, TP-002, TP-004, TP-005)

Introducir `PaymentCoverage` cerrado: `Unpaid`, `Partial`, `Paid`, `InvalidUnknown`. Se calcula con líneas válidas, total decimal positivo y registros de una sola operación. `Unpaid` requiere ledger conocido y vacío; `Partial` suma positiva menor al total; `Paid` suma exactamente igual; sobrecobertura, entradas desconocidas, asociación ambigua o historial de reversas/pagos no finalizados resultan inválidos para nuevas escrituras. No inferir ausencia de pagos de una lectura fallida.

Una política pura de transiciones recibe snapshot tipado de venta, evidencia, flags de bloqueo/retiro y cobertura/historial. Devuelve decisión cerrada `NewCommand`, `RecoverExistingCommand`, `TerminalReplay` o denegación con motivo tipado (venta ausente/ambigua, estado desconocido/no elegible, evidencia inválida, saldo desconocido, moneda inválida, sobrecapture, cobertura incompleta, historial de pagos, intento activo). Etiquetas y reglas viven en estos tipos; el dominio no importa Spring, HTTP, Angular ni SQL.

Capture admite únicamente snapshot elegible de reserva con saldo conocido y `0 < amount <= pending`; fee no resta el saldo ni suma cobertura. Commit admite cobertura `Paid` y estado elegible; release admite sólo historial vacío y reserva elegible. No introducir `PAYMENT_CAPTURED` sólo por capturar una fracción: cobertura se calcula, no se deriva de un estado textual. Preservar los replays existentes compatibles como lectura sin outbox/HTTP/escritura nueva; estados terminales no habilitan operaciones diferentes.

## Puertos y persistencia (TP-006)

Crear/reutilizar puertos separados de consulta de venta por operación y ledger por operación. `CounterApplicationService.capture` consulta ambos y ejecuta la política antes de `PaymentBook.capture`/`savePayment`; `LocalSaleSagaService.commit/release` usa la misma semántica antes de agregar comando o invocar inventory. Evitar dependencia circular entre esos servicios: puertos de lectura compartidos y composición en infraestructura.

Ambos puertos reciben identidad de venta inequívoca y cuádruple `(client_instance_id, device_id, sale_id, operation_id)`, no sólo un texto operationId. Resolver exactamente una venta y validar todos sus campos antes de consultar/guardar pagos; cero o más de una asociación deniega. `InMemoryCounterEntryStore` conserva esa asociación para cada pago/reversa. JDBC usa `sale_state_projection.operation_id` como filtro inicial, valida cuádruple/venta completa y cardinalidad, luego lee ledger de esa venta. Una colisión operationId entre identidades no mezcla pagos ni selecciona arbitrariamente el primero. Si la proyección no provee prueba inequívoca, deniega y registra la limitación; no inventa asociación ni amplía schema sin revisión.

Reversa resuelve el pago original y comprueba que pertenece exactamente a la identidad del request antes de insertar. Su lectura conserva estado, importe, asociación, referencia al original e historial suficiente para invalidar cobertura/release. Puede usar una proyección de ledger tipada sin rehidratar `PaymentRecord` cuando sus invariantes de construcción no lo permiten. Kotlin HTTP y JDBC traducen medios/status futuros, null o corruptos a Unknown en el borde; no `valueOf` sin traductor ni conversión a excepción como sustituto de Unknown. El desconocido deniega capture, commit, release y reverse. No borrar evidencia para obtener saldo cero.

El total se calcula con cantidad y precio efectivo de `TicketLine` en aritmética decimal exacta. Política monetaria compartida compatible con `NUMERIC(14,2)`: valor absoluto <= 999999999999.99 y scale efectiva <=2 tras quitar exclusivamente ceros finales exactos. Validar precio/descuento, importe, fee, total y sumas derivadas antes de persistencia. Importe/total positivos, fee/descuento no negativos; no redondear, truncar ni delegar normalización al driver. `18.001`, `0.001` y cada término de `10.005 + 7.995` se rechazan; `18.000` puede normalizarse exactamente a `18.00`. Paridad memoria/JDBC evita aceptar localmente lo que PostgreSQL redondearía. No confiar en `amount` UI como total. No añadir tablas, migraciones ni atomicidad multiinstancia sin nueva revisión.

## Coordinación local y recovery existente (TP-002, TP-004, TP-005, TP-006)

Un único coordinador compartido en composition root serializa por identidad completa/venta el tramo lectura→decisión→efecto de capture, release, commit y reverse; todos los servicios usan la misma instancia y clave estable. Resolver/cotejar identidad y tomar el guard de la venta antes de leer saldo/historial; operaciones del mismo ticket nunca usan guards distintos por action o paymentId. Mantener el guard hasta la publicación local del efecto/outbox/estado y liberarlo siempre ante excepción. Evitar recursión/deadlocks al recuperar comandos; recovery usa el mismo protocolo coordinado. No basta un lock por servicio o proteger sólo `savePayment`.

Pending deniega `NewCommand`; permite `RecoverExistingCommand` sólo al hallar un comando original compatible con cuádruple, kind, path/version/digest/hash/body/evidencia preservados. Conserva timeout→GET y CONFLICT→GET→same-body rePOST cuando la política vigente lo indique, sin generar otro UUID, reconstruir body distinto ni duplicar outbox. `TerminalReplay` compatible devuelve existente sin nuevo efecto; terminal incompatible deniega. Este corte preserva recovery local existente y no introduce recovery durable.

La exclusión cubre carreras locales capture/capture, capture/release y reverse/commit. No promete coordinación entre procesos, transacción distribuida ni persistencia tras reinicios. Un crash entre efectos continúa siendo límite explícito.

## Frontend y pasos (TP-001, TP-002, TP-003, TP-006)

Reusar clases con constructor privado/instancias estáticas y `fromWire` único de `pos-types.ts`, extendiendo reglas y añadiendo tipos de cobertura/decisión donde corresponda. `PosWireMapper` valida envelope, payload, identidad esperada, evidencia necesaria y estado antes de exponer snapshot de dominio. Una respuesta desconocida deniega; conservar identificadores para lectura/evidencia, sin convertirlos en autorización.

Fijar DTO propio BlackStore `PaymentResponse`: `operationId`, `paymentId`, `status`, `amount`, `feeAmount`, más identidad completa de venta/cuádruple para correlación inequívoca. `paymentId` positivo; estado Captured; identidad coincide exactamente con contexto inmutable del intento; amount/fee se comparan como decimales exactos con lo solicitado. Cualquier campo ausente, inválido o distinto detiene split, sin retenerlo como captura válida. Cambiar el legacy `id` a `paymentId` requiere actualizar consumidor y tests juntos; no alterar contrato StoreCore.

Reserva y GET/snapshot BlackStore exponen cuádruple/identidad de venta, estado, receipt/reservationRef y evidencia suficiente validada por backend, total de líneas, cobertura tipada, saldo y presencia de historial de pagos. El mapper no puede validar evidencia omitida: debe recibirla o una proyección explícita de decisión validada backend con versión/correlación de snapshot. El diseño de este corte elige snapshot explícito y contexto inmutable de request; refresh confirma identidad y recompone elegibilidad antes de commit/release o captura posterior. Respuestas tardías de otro intento se descartan. Campos Unknown o saldo ausente deniegan.

Mover orquestación de `sale-ticket.component.ts` a `ReserveTicketStep`, `CapturePaymentStep`, `RefreshTicketStep`, coordinados por recorrido. Adquirir el guard UI del submit antes de generar UUID/contexto nuevo; doble submit activo no crea otra operación. Cada capture split usa el paso con importe/fee del intento y exige respuesta Captured válida; luego refresca snapshot/cobertura antes del siguiente. Cancelación/error finaliza el intento sin autorizar retry ciego; respuestas tardías no cambian otro contexto. Sin claim idempotencia global.

Las acciones reserve/capture/commit/release consumen decisiones tipadas. Backend sigue siendo autoridad aunque una llamada HTTP omita la UI. No abrir un contrato StoreCore nuevo; cambios de payload BlackStore necesarios para representar cobertura requieren tests de mapper y compatibilidad local.

## Matriz mínima de tests (TP-001..TP-006)

- **T01 / TP-001:** traductores frontend y Kotlin HTTP/JDBC con casos conocidos y futuro/null/vacío/malformed para medio/status; Unknown deniega y etiqueta neutral. Historial de reversa se lee aun sin rehidratar PaymentRecord. Literales sólo en tests del traductor.
- **T02 / TP-002:** reserva Reserved válida permite siguiente paso; pending/reconciliation/unknown/missing/envelope inválido/operationId distinto/evidencia ausente no capturan.
- **T03 / TP-002:** backend venta ausente, sin líneas, total cero, retired, blocked y estado no elegible rechaza capture sin `savePayment`.
- **T04 / TP-002:** ticket 18: capture 10 válido; posterior 8 válido; posterior 9 rechazado; cero/negativo/saldo desconocido rechazados. Fees no alteran cobertura. Paridad memoria/JDBC rechaza 18.001, 0.001, 10.005+7.995 y overflow; acepta ceros finales normalizables y límite de rango sólo si saldo lo permite. No redondeo implícito de precio/fee/total.
- **T05 / TP-003:** split sólo tras Captured validado exactamente por identidad/operationId/paymentId/amount/feeAmount; missing, amount/fee distintos, id inválido, pending/unknown/fallo no avanzan. Doble submit antes de UUID prueba una única creación y request; intercalado/respuesta tardía de otra operación no cambia intento. Refresh exige snapshot correlacionado.
- **T06 / TP-004:** ticket 18 con pagos 0/10/18/19: únicamente 18 produce commit nuevo; sin comando/outbox/llamada inventory en los otros casos.
- **T07 / TP-004, TP-006:** pago de otra operación, ledger desconocido y total malformado no autorizan commit/capture.
- **T08 / TP-005:** release con historial vacío permite; parcial/completo/pending/unknown/reversado/void bloquea, aun neto cero.
- **T09 / TP-004, TP-005:** distinguir NewCommand, RecoverExistingCommand y TerminalReplay. Pending no crea nuevo; GET y CONFLICT→GET→same-body rePOST preservan cuádruple/body/hash/outbox únicos. Comando original ausente/incompatible deniega recovery; terminal replay compatible no crea efectos; terminal incompatible/retired/blocked/reconciliation/unknown deniega nuevo.
- **T10 / TP-006:** contrato memoria/JDBC con identidad completa: aislamiento A/B, mismo operationId bajo cuádruples distintas, cero/múltiples asociaciones fail closed, reversas visibles. Reversa de pago ajeno rechazada antes de insert. Prueba JDBC real donde el runner lo soporte; traductores de ledger corrupto no simulan vacío.
- **T11 / TP-001..TP-006:** HTTP rechaza bypass UI para capture/commit/release/reverse; UI no envía denegadas. Tests deterministas con barreras, sin sleep, fuerzan intercalado de capture/capture (dos 10 en total18: máximo uno guarda), capture/release (máximo un camino compatible; nunca pago sobre venta liberada), reverse/commit (reversa previa bloquea commit; commit previo deniega reversa incompatible). Verificar guard compartido y relevo tras excepción; ningún outbox/efecto extra.
- **T12 / TP-006:** regresión de config disabled/fixture/kill switch/canary 0 y rutas existentes; no llamada real ni cambio de perfil.

Registrar resultados con HEAD, comando, assertions ejecutadas y limitaciones. Runner que no alcanza assertions no cuenta como pass; Testcontainers sin Docker no demuestra JDBC. No atribuir tests previos a este corte.

## Exclusiones y gate

Sin incorporar recovery durable, nueva política de retry, inbox/outbox durable, atomicidad transaccional pago+saga, reportes, fiscal ni live. Se preserva recovery del comando existente y se exige serialización local compartida; esto no prueba seguridad entre procesos. Sol debe volver a revisar el draft corregido antes de implementación; validación final no concede deploy, rollout ni `/sdd.finish`.
