# ADR-002 — Admisión de saga v2, lifecycle y recuperación de intenciones UI

Estado: accepted; GO DE DISEÑO específico autorizado explícitamente por el usuario, 2026-10-07. Revisiones documental y Security: APPROVE sobre exact head `0b4cddc7f26ec6965ad81443ec4cde74aadd2ce5`, según registro del coordinador en [evidencia T08-A](../../evidence/CLR-T08-A-contracts-20261007.md). Corte documental CLR-T08-A, base exacta `9e6e2cb0522bf2e001245a1593edeea2bb3136a6`. Habilita continuar los cortes planificados, no acredita implementación/runtime ni autoriza activación/homologación. Complementa [ADR-001](ADR-001-accounting-locks-time-legacy.md) sin reemplazar las garantías del ledger o el contrato canónico StoreCore.

## Problema comprobado y alcance

En la base, `JdbcSaleRecordStore.recordIntentAndOutbox` y `admitTerminal` admiten writers Legacy. La UI migró caja, egreso, captura y reversión, pero reserva/commit/release siguen bajo v1. PRE_ACTIVATION admite reserva v1 y rechaza captura v2; ACTIVE rechaza la saga v1. Ninguno de esos estados acredita el flujo mixto completo. `WorkspaceResponse` no publica lifecycle; `AccountingRuntimeQuery` sólo selecciona semántica de pagos. `AccountingCommandsStore` pierde la intención en reload y la limpia en `session.changed`.

T08-B entregará contratos/backend/schema y T08-C clientes v2, lifecycle preventivo y journal durable. T09 acreditará browser/PG16/reinicio/CI/reviews. T08 sigue parcial. No migrar ni cambiar obligatoriamente el wire v1; sus GET de venta pueden reutilizarse como lecturas hasta que otro contrato los versione.

## Contrato HTTP de admisión de saga

Rutas propuestas BlackStore, distintas del contrato StoreCore `/blackstore-integration/v1`:

- `POST /api/v2/sales/reservations`: `commandId` UUID obligatorio, cuádruple `clientInstanceId/deviceId/saleId/operationId`, `cashSessionId`, `variantId`, `quantity`, `expectedPriceVersion`, campos actuales de línea de ticket y `reason` nullable. Normalizar importes y motivo una vez antes del fingerprint; precios/catálogo se validan con la fuente autorizada, nunca por confianza en el browser.
- `POST /api/v2/sales/{operationId}/commit` y `/release`: `commandId` UUID, cuádruple completa y `reason` nullable, obligatorio cuando la política vigente exige override. El operationId del path coincide exactamente con el cuerpo. La reserva/evidencia remota se carga del backend; el browser no aporta una reserva autoritativa nueva.
- `GET /api/v2/sales/commands/{commandId}`: consulta local read-only del recibo; no ejecuta worker, HTTP StoreCore, re-POST, reconocimiento ni actualización de proyección.

Cada tipo de comando es un caso cerrado `Reserve`, `Commit`, `Release`; `Unknown` externo bloquea. Kotlin enum/sealed y TS clases de constructor privado/instancias estáticas, con un único traductor en el borde. Dominio sin Spring/JDBC/HTTP/Angular; etiquetas, permisos y reglas viven en los tipos/políticas. No aceptar actor, rol, lifecycle ni elección de writer en JSON.

`SaleCommandAdmissionReceipt` es inmutable y separado de `AccountingCommandReceipt`: contiene commandId, kind, payloadHash canónico, actorId original, cashSessionId, cuádruple, referencia de intención/outbox y acceptedAt del reloj DB. El fingerprint versionado incluye kind, actor original, identidad, caja y todos los datos semánticos normalizados de la intención; no incluye SID, CSRF ni timestamps del browser. En replay se reconstruye con el actor original, tras autorizar al actor actual. El mismo ID no cambia de kind.

Resultados de consulta cerrados: `Accepted(receipt)`, `NotFound`, `Unavailable`, `Unknown`. POST nuevo confirmado responde 202 Accepted; replay devuelve el mismo recibo con 200; GET encontrado 200; NotFound 404; Unavailable 503. Un 202 sin recibo válido no acredita nada. El envelope conserva BaseResponse, traceId y `Cache-Control: no-store`. Los traductores validan UUID, refs, hash, timestamp, kind y correspondencia con la intención.

**Accepted acredita sólo commit local de admisión + intención + outbox + auditoría. No significa RESERVED, venta COMMITTED, RELEASED, pago capturado ni reconocimiento comercial.** Después del recibo, la UI obtiene la venta autoritativa y verifica su evidencia/saldo antes de avanzar. Un comando aceptado puede seguir pendiente o requerir conciliación; no se transforma en recibo contable de éxito. El worker conserva su garantía de reconocimiento exactamente una vez al aplicar COMMITTED.

Rechazos cerrados: Validation (400), Forbidden (403), NotVisible (404 opaco), PayloadMismatch, ExistingOperationCommand, Closed y TransitionConflict (409), NotActivated/Paused/LegacyContractDisabled conforme al mapa contable existente, Unavailable (503), Unknown neutral. El traductor de errores no expone SQL ni wire privado. Un mismo commandId/cuerpo autorizado retorna su recibo aun con caja cerrada o runtime pausado, sin despacho adicional; cuerpo/kind distintos rechazan mismatch sólo después de visibilidad. Otro commandId para la misma operación/kind ya admitida devuelve ExistingOperationCommand, nunca inserta un segundo outbox ni reemplaza evidencia. Los ID históricos v1 no reciben recibos v2 inventados; un intento de adoptarlos queda en conflicto/conciliación explícita.

La consulta NotFound no demuestra que sea seguro generar otra intención. Se conserva Unknown y el bloqueo. Sólo una acción humana explícita puede reenviar el mismo commandId/payload congelados, con permisos/CSRF/lifecycle actuales. No habrá reenvío POST automático del browser por timeout, reload, login, NotFound ni polling; T08-C puede limitar la acción a consultar. Esto no cambia la política durable server-side StoreCore (GET tras incertidumbre y re-POST de la misma cuádruple cuando la matriz canónica lo permite).

## Autoridad y matriz de permisos

SID persistente sigue siendo la única autoridad; el filtro de rutas debe clasificar explícitamente estas rutas v2 y conservar cookie/CSRF. Todas las nuevas mutaciones revalidan staff ACTIVE, permiso y ownership tras locks. No ampliar la elegibilidad del titular de caja ni delegar autoridad a storage local.

- Reserve exige `SaleReserve`; commit `SaleCommit`; release `SaleRelease`. CASHIER opera sólo su caja; SUPERVISOR/OWNER operan según permiso vigente, con motivo para caja ajena. AUDITOR/Unknown nunca mutan.
- GET de recibo de saga exige el permiso actual del kind original y visibilidad actual de su caja, pero no caja OPEN ni un motivo nuevo (no es otra mutación). CASHIER ajeno recibe el mismo 404 que inexistente; SUPERVISOR/OWNER pueden consultar según política. AUDITOR queda denegado para recibos de mutación. No otorgar ese acceso usando solamente SaleRead.
- POST replay vuelve a comprobar permiso/ownership y el motivo congelado cuando corresponda, sin exigir una transición nueva ni alterar actor original. Staff revocado/inactivo, rol degradado o caja no visible no obtienen datos anteriores.
- Agregar permiso cerrado `AccountingRuntimeRead` para CASHIER, SUPERVISOR, OWNER y AUDITOR activos. Sólo publica metadata del lifecycle de instalación; no concede WorkspaceRead, SaleRead, recibos ni escritura al auditor.
- Sin sesión: 401. Rol no habilitado: 403 sin consultar/revelar existencia. Para quien tiene permiso, inexistente/no visible: 404 indistinguible antes de mismatch/conflicto. Denegaciones relevantes conservan auditoría independiente SID/DCT sin filtrar recibos ni producir éxito.

Aplicar la misma regla vigente de permiso del comando original a los recibos contables rehidratados; el journal no crea una excepción a CLR-009. Si el adaptador actual necesita alineación para satisfacerla, T08-B incluye esa corrección y pruebas, no sólo el endpoint de saga.

## Transacción, migración forward-only y recuperación

Proponer `V10__sale_command_admission_receipts.sql` tras verificar numeración sobre la base de integración. No crearla en T08-A. V1–V9 y sus checksums permanecen intactos. Tabla nueva append-only de recibos con UUID único, kind CHECK cerrado, hash, FKs a intención/outbox/caja/actor, timestamps DB y unicidad de operación/kind alineada con el outbox existente. Validar correspondencia de recibo/intención/outbox mediante constraints/triggers; no filas huérfanas ni identidad contradictoria. `commandId` se identifica junto a la familia tipada Accounting/Sale en clientes/consultas; no se consulta una familia para resolver otra. Una intención nueva nunca reutiliza deliberadamente un UUID previo.

INSERT/SELECT mínimo para app y lecturas estrictamente necesarias para worker/auditor; UPDATE/DELETE históricos prohibidos por grants y triggers. No ensanchar grants de ledger ni usar migration_owner en runtime. No backfill de recibos, postings, reconocimiento o cobertura para historia v1. No agregar kinds de saga al CHECK de recibos contables de V8.

Un puerto `SaleMutationCommands` ejecuta pasos de una responsabilidad: canonizar/validar, localizar autoridad, serializar, bloquear, revalidar, resolver replay, admitir lifecycle, insertar intención/outbox/recibo/auditoría. Extraer helpers transaccionales compartidos cuando corresponda; no llamar al servicio v1 con un flag ni envolver conexiones independientes con @Transactional. El endpoint no acepta un writer arbitrario.

Orden global de adquisición: fence lifecycle compartido, serialización commandId y operación, caja, venta, delivery. Todas las rutas que compartan esos locks respetan el mismo orden, incluido worker/cierre. Lookup sólo localiza; autoridad/estado/evidencia se revalidan tras espera. Replay autorizado resuelve antes de exigir admisión de una escritura nueva. PreActivation rechaza nuevas v2, Active admite v2 y bloquea writers v1 afectados, Paused bloquea nuevas admisiones/aplicación de evidencia por worker. La transición lifecycle espera el fence exclusivo; no existe ventana de doble writer.

Confirmar intención + outbox canónico (cuádruple/path/version/digest/hash/payload) + recibo + auditoría juntos antes de HTTP. Despacho fuera de transacción/locks. Crash precommit deja ninguno; crash postcommit deja recibo/outbox recuperables. Un recibo no se emite como válido antes de commit. Crash tras efecto remoto y antes de aplicar evidencia conserva incertidumbre y fuerza GET de la misma cuádruple. Aplicación/reconocimiento son atómicos e idempotentes conforme al runtime durable actual, sin segundo receipt de admisión.

Paused permite consultar evidencia ya persistida pero no escribirla/reclamar nuevo trabajo. Si pausa gana después del HTTP remoto y antes de aplicación local, conservar comando/lease durable para recuperación tras reanudación autorizada; GET del browser no hace esa recuperación mutante. Mantener guards fiscales, contrato pinneado y restricciones de conector. Restore/rollback sólo en DB aislada o con binario compatible; ningún rollback reactiva Legacy ni revierte migraciones/hechos.

## Lectura lifecycle y admisión preventiva UI

`GET /api/v2/accounting/runtime`, permiso AccountingRuntimeRead, no-store y consulta read-only: `state` cerrado PreActivation/Active/Paused/Unknown, `activationAt` nullable coherente, `contractVersion` y `observedAt` del backend. Ausencia/error/esquema desconocido produce Unknown/Unavailable y bloqueo. No inferir estado por existencia de postings, URL, environment frontend o error previo. La lectura no es lease de autorización ni requiere inventar un revision token para este corte.

Nuevo puerto `AccountingLifecycleQuery` y adaptador JDBC separados del puerto actual `AccountingRuntimeQuery.paymentLedgerSemantics`. El backend continúa revalidando dentro de cada mutación ante una carrera con pausa/activación. UI sólo permite nuevas operaciones con sesión vigente, lifecycle Active conocido, hidratación terminada, permiso/ownership y ausencia de intención incierta. PreActivation bloquea esta consola v2, aunque otros clientes legacy autorizados conserven su contrato previo. Sin fallback browser v1.

Leer en bootstrap, cambio de sesión y recuperación de foco; invalidar al perder frescura/contexto y ante fallos tipados de lifecycle. Durante refresco preventivo, bloquear nuevas intenciones; GET de recuperación permanece disponible con autoridad actual. Tipo TS cerrado + PosWireMapper + store lifecycle + política compartida por template y handler, no strings mágicos.

## Journal browser durable, identidad y pestañas

Puerto de dominio/aplicación y adaptador IndexedDB. Guardar antes del primer POST: versión de journal, familia/kind, commandId, payload canónico congelado, referencias de operación/caja, staffId autenticado que creó la intención, ámbito de instalación/origen/dispositivo y fase cerrada. No persistir SID, CSRF, password ni permisos como autoridad. El ámbito procede del contexto de instalación verificado; si falta/contradice evidencia, fail-closed, no inventar una instalación. Storage es evidencia cliente no confiable, jamás autoridad del backend ni mecanismo contra XSS.

Fases mínimas cerradas: Prepared, AwaitingReceipt, ReceiptVerifiedAwaitingRefresh, Resolved, Quarantined y Unknown. Una intención Prepared tras reload puede haberse enviado antes del crash; consultar primero, nunca asumir que no llegó. La familia selecciona el endpoint correcto y el recibo esperado. Validar tamaño/esquema/ref/UUID/valores cerrados al rehidratar; corrupción o versión desconocida pone el flujo afectado en cuarentena, no se borra silenciosamente.

Bootstrap: autenticar y obtener CSRF actual, leer lifecycle y journal del ámbito, rehidratar tipo, GET del mismo commandId, validar correspondencia y refrescar la entidad/saldo autoritativos. Recibo válido sin refresh exitoso queda ReceiptVerifiedAwaitingRefresh y bloquea el paso siguiente. Persistir la resolución antes de permitir una intención nueva. La evidencia mínima resuelta puede retenerse sin payload sensible; retención/purga sólo tras resolución confirmada, nunca de pendientes para desbloquear.

`session.changed` cancela requests, incrementa generación y limpia señales/referencias visibles, pero no elimina evidencia durable incierta. Nuevo SID del mismo staff permite GET con autorización actual; nunca POST automático. Otro staff no ve ni adopta el payload/recibo anterior. Un marcador neutral de intención pendiente en caja/dispositivo compartidos impide generar otra intención sobre el mismo ámbito hasta recuperación autorizada; no bloquea por inferencia todas las cajas de otros usuarios. La recuperación por supervisor debe pasar permisos/ownership server-side y flujo explícito revisado, no revelar el journal privado ni ofrecer un botón de olvidar. Si no puede resolverse en este corte, mantener bloqueo y handoff operativo, no limpiar la entrada.

Usar transacción/claim IndexedDB por ámbito para serializar pestañas antes del POST; BroadcastChannel puede notificar, no sustituye la exclusión durable. Expirar un claim de tab permite consultar, nunca crear automáticamente otro commandId. Storage deshabilitado/cuota/error antes del envío impide el POST. Respuestas y finalize de generación/comando anterior no limpian una intención nueva. Recibos compartidos por múltiples suscriptores se correlacionan también por identidad del comando, preservando la regresión T08 existente.

La garantía alcanza reload/reinicio conservando el almacenamiento del perfil. Borrado deliberado de datos del sitio, pérdida de dispositivo o perfil privado sin storage no acreditan recuperación desde browser; consultar historia autoritativa y conciliación operativa antes de reemitir. No prometer protección ante pérdida total de la evidencia cliente ni resolverla inventando un ID nuevo.

## Comisión y alcance excluido

PaymentCapture v2 no admite `feeAmount`: T08-B debe rechazar explícitamente su presencia (incluso cero/null), sin ignorarlo por configuración del deserializador. Eliminar entradas/argumentos UI que sugieran captura con comisión. Mantener lectura de comisión histórica con su semántica/completitud; no presentar cero de captura como prueba de ausencia de comisiones pagadas.

FeeRecord permanece capacidad interna OWNER, commandId propio, medio efectivo de desembolso, motivo/evidencia y auditoría, sin endpoint browser. Comisión informada no acredita FEE pagada. Ampliar captura con comisión informativa o crear UI/endpoint FEE requiere contrato separado; fuera de T08-B/C. Sin RMA pos-COMMIT ni otras capacidades nuevas.

## Archivos previstos y pruebas de aceptación

Backend B: nuevos `domain/sales/SaleCommand*.kt`, puerto `domain/port/out/sales/SaleMutationCommands.kt`, `application/sales/SaleCommandApplicationService.kt`/fingerprint/pasos, DTOs v2 y `presentation/controller/SaleV2Controller.kt`, `JdbcSaleMutationCommands.kt`, puerto/query/controlador lifecycle y migración V10. Integración acotada con JdbcSaleRecordStore/JdbcDurableSaleRepository/JdbcAccountingAggregateLocks/admission/beans y filtro/política de permisos; no reescritura del flujo durable StoreCore.

Frontend C: dominio/traductor de saga y lifecycle; journal port/adapter/mapper; AccountingCommandsStore, SessionStore, nuevo store de comandos saga, DurableSalesStore, SaleTicketComponent, ticket-steps y PosWireMapper. Caja/pagos/saga comparten journal/coordinación, sin dejar captura como excepción en memoria.

Pruebas requeridas (todas NOT_RUN para estos contratos):

- Contract/domain: casos cerrados/Unknown, canonicalización estable, UUID ausente/inválido, refs/envelope/hash incoherentes, path/cuerpo mismatch, feeAmount presente, Accepted sin terminalidad ni posting.
- Seguridad HTTP: sin SID, CSRF ausente/incorrecto, staff inactivo/revocado durante lock, CASHIER propio/ajeno, supervisor con/sin motivo, OWNER, AUDITOR y rol Unknown; permiso degradado al recuperar; recibo inexistente/no visible indistinguibles; GET sin escrituras/dispatch.
- PG16 real: clean V1→V10, upgrade V9 poblada, grants/triggers/rollback/roles restaurados e historia intacta. Carrera mismo commandId igual/distinto payload, rollback del ganador, kind cambiado y otro ID para operación/kind existente.
- Concurrencia: reserva/cierre, commit/release, pago/commit, reversión/release, worker/reconocimiento/cierre y lifecycle/admisión/aplicación en ambos órdenes con dos conexiones/barreras; sin locks invertidos ni hechos duplicados.
- Crash PID propio: antes de commit de admisión, después de commit antes de respuesta, después de efecto remoto antes de aplicación local, después de aplicación antes de entrega. Reinicio conserva mismo ID/cuádruple y un solo outbox/hecho/recognition; pausa intermedia conserva evidencia.
- UI: reload pre/postPOST, storage fallido/corrupto, NotFound persistente, mismo staff/nuevo SID, actor diferente, respuesta tardía, dos tabs, receipt válido con refresh fallido, consulta concurrente y nueva intención; cero POST de recuperación automática.
- T09 browser CLR separado de DCT: reserve→capture→commit; reserve→capture→reverse→release; split, respuesta perdida de RELEASE, backend restart+reload; PRE_ACTIVATION bloquea consola v2, ACTIVE rechaza POST v1, PAUSED bloquea mutaciones y mantiene GET; arqueo/reportes y matriz CLR-010 completa.

## Gates y evidencia

T08-A done por documentación, validaciones JSON/links/diff, APPROVE documental/Security sobre `0b4cddc7f26ec6965ad81443ec4cde74aadd2ce5` y GO DE DISEÑO explícito del usuario. Este amend registra el resultado sin cambiar los contratos revisados; no atribuye al nuevo SHA una nueva revisión ni hereda aprobaciones de implementación. T08-B/C permanecen planned y requieren sus propios tests/reviews. T09 no inicia aceptación hasta que clientes internos mutantes sean todos v2 y journal/lifecycle estén implementados.

Cada corte registra source/head/build/migraciones/PG major, regresión relevante, CI y reviews independientes bugs, seguridad/arquitectura y SDD sobre head exacto. Un cambio posterior invalida aprobación heredada. PG18, MockMvc, jsdom/Vitest, discovery y CI verde no equivalen a PG16/browser/crash ejecutados. Activación exclusivamente en DB aislada del harness para assertions no habilita runtime operativo. Homologation BLOCKED, Publication NOT_RUN, live/fiscal/canary/companion/facturación/Correo Argentino con gates externos; sin archivo del WIP ni `/sdd.finish`.
