# Especificación técnica

## Evidencia de base y decisión

- `application/cash/CashSessionApplicationService.kt`: `save` y `appendClosureAudit` separados; lectura previa con `list` no serializa cierre.
- `infrastructure/persistence/JdbcCashSessionStore.kt`: conexiones independientes con autocommit para proyección y auditoría.
- `infrastructure/persistence/JdbcBlackStoreWriter.kt`: cierre ya usa `WHERE id = ? AND status = 'OPEN'` y comprueba `updated == 1`; conservar ambas defensas. La brecha es la transacción separada de auditoría y autorización fuera del lock, no falta de condición SQL. La inserción de egreso no devuelve al caso de uso identidad durable.
- `application/counter/CounterApplicationService.kt` y `infrastructure/persistence/JdbcCounterEntryStore.kt`: autorización previa al insert de egreso; transacción JDBC sin lock/relectura de caja, ID de aplicación no autoritativo.
- `domain/cash/CashSession.kt`, `domain/sales/PaymentTransitionPolicy.kt`, `application/identity/AuthorizeStaffAction.kt`: reutilizar tipos, dinero y política de autorización existentes, sin reglas paralelas por texto.
- `resources/db/migration/V1__blackstore_schema.sql`: índice parcial único por terminal `OPEN`, FKs, cantidades `NUMERIC(14,2)`, auditoría/egresos inmutables y roles separados ya disponibles. V4–V7 aportan identidad/runtime, no atomicidad de caja.

Los paths anteriores se resuelven bajo `backend/src/main/`. Fuentes son la base exacta de `meta.md`; no se toman viejos checks de piloto como evidencia de esta garantía.

## Arquitectura y objetos de flujo

Los servicios dependen de puertos de comandos atómicos, no de JDBC. Sustituir la composición pública `save + appendClosureAudit` para escrituras de caja por apertura/cierre atómicos. Conservar lectura separada. Un puerto de egreso devuelve el registro persistido. El adaptador de memoria debe respetar las mismas invariantes en tests/fixture, sin ser fallback del runtime persistente.

Pasos con una responsabilidad: cargar autoridad persistida, bloquear/cargar sesión, evaluar política tipada, persistir decisión con auditoría. El coordinador ordena los pasos; dominio no importa Spring/JDBC/HTTP. El límite transaccional vive en infraestructura, usando una conexión explícita compartida por todos los escritores del comando. Añadir `@Transactional` alrededor de métodos que abren conexiones propias no satisface el contrato.

Reutilizar `CashSessionStatus`, `StaffRole`, `StaffPermission`, `PaymentMethod`, `MoneyPolicy`; resultados/rechazos y evento de egreso se modelan con enum/sealed y `Unknown` donde consumen valores externos. El único traductor wire/JDBC relevante produce el tipo antes de aplicar reglas; no comparar textos ni duplicar etiquetas en vista/store/tests. Ampliar el mapper de método de egreso si actualmente Jackson rechaza el valor antes de llegar al traductor, conservando 400 fail-closed.

## Transacción y locks

### Resultado cerrado, SID y HTTP

Definir `CashMutationResult`/`CashMutationFailure` enum o sealed en `domain/cash`: `Applied`, `NotVisible`, `Forbidden`, `Validation`, `Conflict`, `Unavailable`, `Unknown`; aplicado lleva el registro. Conversión SQL→resultado discrimina índice conocido y rowcount, sin comparar mensajes. Conservar SQL `OPEN` y rowcount del writer, sustituyendo error genérico por resultado tipado después de comprobar visibilidad.

Targets: `domain/identity/StaffIdentity.kt` (`StaffAuthorizationPolicy`) y `application/identity/AuthorizeStaffAction.kt` ofrecen comprobación de visibilidad/permiso separada de elegibilidad `OPEN`, usada sólo en comandos DCT tanto en precheck como en transacción. Retirar de esa ruta el precheck `ExpenseRecord && !open -> NOT_FOUND`, que impediría alcanzar el 409 contratado. Otros callers conservan SID. Orden: permiso global, existencia/estado conocido, ownership/visibilidad, motivo override, elegibilidad. Caja ajena a Cashier corta antes de divulgar estado o conflicto.

`infrastructure/exception/GlobalExceptionHandler.kt` mapea fallos cerrados: `NotVisible`→404 `NOT_FOUND`, `Conflict`→409 `CASH_SESSION_CONFLICT`, `Validation`→400 `VALIDATION`, `Forbidden`/`Unknown`→403 genérico, `Unavailable`→503; autenticación sigue 401 SID. Mensajes seguros constantes, `retryable=false` y `Cache-Control: no-store`. `presentation/controller/CashSessionController.kt` y `CounterController.kt` usan staff confiable y servicios/mappers, sin filtrar SQL. Una violación de índice de apertura primero revierte; lectura posterior decide 404/409 según visibilidad. Ausencia/inconclusión de visibilidad no se convierte en conflicto; fallo DB sigue 503.

Targets Angular desde raíz: `frontend/src/app/core/domain/pos-types.ts` define resultado con constructor privado, instancias estáticas, `fromWire` único, `Unknown` y etiquetas/acciones propias. `core/infrastructure/pos-wire-mapper.ts` valida status/envelope y traduce antes de vista/store. `features/cash/cash-session.component.ts` elimina impresión de errores crudos en estas mutaciones, bloquea snapshot viejo, recarga sólo GET ante conflicto, borra selección ante no visibilidad y descarta respuestas de otra generación de identidad. `core/services/counter-context.service.ts` invalida/recarga contexto sin retry POST. Sin rediseño Angular.

Tests HTTP propuestos en `presentation/controller/CashMutationHttpPostgresTest.kt`: propia cerrada cierre/egreso→409; ajena abierta/cerrada y ausente→404 con mismo envelope salvo traceId; Unknown→404; override sin motivo→400; Auditor→403; anónimo→401; doble apertura propia→409, colisión ajena→404. Verificar cero mutaciones/auditorías de éxito en rechazos y que carreras esperadas no terminan en 500. Tests UI `features/cash/cash-session.component.spec.ts`, `core/domain/pos-types.spec.ts`, `core/infrastructure/pos-wire-mapper.spec.ts`: 409→GET sin POST, 404 borra selección, Unknown neutral, cero raw message y respuesta tardía ignorada tras cambio de identidad. MockHttp sólo acredita UI; HTTP/PostgreSQL y browser reales cierran Integration.

1. Apertura: iniciar transacción, revalidar cuenta/rol, elegibilidad del cajero y autorización del actor sobre el propietario solicitado; normalizar importe exacto; insertar proyección y auditoría con el ID generado; commit. Los índices existentes `uq_open_cash_session_per_terminal` (V1) y `uq_open_cash_session_per_cashier` (V4) deciden carreras de terminal y cajero aunque una preconsulta vea terminal libre. Únicamente SQLSTATE `23505` con uno de esos nombres de constraint entra al resultado de carrera y filtro 404/409 anterior; cualquier otra violación DB conserva 503 genérico. Rollback elimina también auditoría. La consulta posterior incluye ambas claves; si cualquier caja bloqueante es ajena al Cashier o no se establece visibilidad, no divulga conflicto. No hacen falta mutex JVM ni lock sobre una caja inexistente. Ajuste aprobado explícitamente por el coordinador al descubrir el índice V4 durante implementación, sin schema ni grants nuevos.
2. Cierre: iniciar transacción, adquirir `SELECT ... FOR UPDATE` por ID de caja, revalidar autoridad/ownership y `OPEN` sobre esa fila, calcular transición con dominio, ejecutar update condicionado a ID y estado esperado y exigir una fila, insertar auditoría, commit. Ningún retry vuelve a modificar caja cerrada. No sustituir apertura, terminal, propietario ni declaración histórica.
3. Egreso: mismo lock de fila y protocolo de revalidación que cierre; comprobar dinero/método/motivo/categoría antes de insertar, devolver ID de DB y auditar en la misma conexión; commit. La revalidación posterior a esperar el lock es obligatoria aunque ya se autorizó en application.

READ COMMITTED con row lock basta para cierre/egreso. Orden: lock de caja antes de lecturas/escrituras de su comando; no adquirir locks de venta ni introducir orden inverso. Consultar rol/cuenta actual en la transacción y alimentar la política SID; no usar `actorId == actorId` como ownership. Apertura valida propietario elegible en DB. No se amplía el contrato SID a cancelación retroactiva de solicitudes admitidas.

`blackstore_app` no tiene UPDATE sobre caja y PostgreSQL exige privilegio UPDATE para `FOR UPDATE`: adquirir lock/update bajo el rol existente `blackstore_projection_worker`, cambiar a `blackstore_app` para auditoría/egreso con `SET LOCAL ROLE`, siempre dentro de la misma conexión/transacción. Apertura usa `blackstore_app`. Conservar grants mínimos y triggers; no conceder UPDATE de históricos ni ejecutar con rol migrador. Probar expresamente que roles se restauran tras commit/rollback y que pool reutilizado no conserva autoridad. Si composición real no permite esta secuencia, detener implementación para revisar este diseño, sin ampliar grants informalmente.

Validar y normalizar `BigDecimal` antes de JDBC, incluida entrada interna fuera de controller. Escala exacta con `RoundingMode.UNNECESSARY`, límite actual de `MoneyPolicy`, cero permitido sólo donde corresponde; PostgreSQL no debe ser quien redondee. Auditoría guarda actor verdadero, aggregate/ID generado y motivo redacted; nunca password, cookies ni CSRF. Auditoría de denegación existente permanece separada del evento de éxito y no prueba una mutación realizada.

## Schema, migración y rollback

### Elegibilidad de titular distinta del permiso del actor

Apertura exige titular existente, ACTIVE y exactamente `StaffRole.CASHIER`; SUPERVISOR/OWNER no son titulares elegibles aunque puedan actuar con permiso CashSessionOpen y motivo override sobre un cajero elegible. JDBC revalida active/rol y memory aplica la misma frontera. Titular missing/inactivo/rol distinto devuelve NotVisible/Authorization → 404 opaco y una denegación independiente; cero caja/success audit. Esto conserva el contrato anterior de `JdbcStaffIdentity.eligibleCashier` (active y role_code CASHIER), sin confundir permiso global del actor con tipo de titular.

### Procedencia cerrada de denegaciones

`CashMutationResult.Rejected` y su excepción conservan `CashRejectionSource` (`Mutation` por defecto seguro, `Authorization` desde `CashMutationPolicy.authorize` o revalidación durable de actor/owner). Ambos adapters propagan esa procedencia después del rollback y cierre de conexión; los blockers ocultos detectados por la misma política también se marcan. Un lookup inconcluso, validación monetaria/categoría/método/motivo largo, conflicto de estado o fallo DB no se transforma en denegación de autorización. Application llama `AuthorizeStaffAction.recordCashDenial` después de recibir el resultado y antes de lanzar el fallo HTTP: escribe una sola `AUTHORIZATION_DENIED` independiente para ownership/permiso/motivo de override, sin evento de éxito ni mutación parcial. Se mantienen 404 oculto, 400 de override y 409 visible. No auditoría de denegación para Conflict/Unavailable o validación común.

**Decisión DCT-ADR-01: no crear migración.** Este corte utiliza tablas, columnas, índice parcial, secuencias y grants de V1–V7; atomicidad y locks no necesitan versión de fila, ledger ni tabla de comandos. No editar migraciones aplicadas. Validar Flyway desde DB nueva y DB V7 poblada, preservando datos y restricciones. Si aparece necesidad de schema, requiere cambio explícito de SDD y nueva migración forward-only antes del código; no reservar una V8 vacía.

Rollback de implementación: revertir únicamente commits del corte, preservando todas las filas y auditorías escritas; no rollback SQL ni reset. Volver al binario anterior reintroduce la brecha original: detener escrituras de caja/egresos hasta aplicar corrección verificada. Para fallo durante un comando, rollback de transacción y cierre de conexión; ante commit incierto consultar DB y no repetir automáticamente egreso. No borrar historia ni inventar auditorías retrospectivas de filas previas.

## Evidencia requerida y fronteras de crash

Harness de PostgreSQL con runtime roles y migraciones reales; fallos de test mediante decorador/failpoint aislado del runtime normal, sin endpoint productivo que permita abortar comandos. En apertura/cierre/egreso inyectar fallo después de primera escritura y durante auditoría, además de fallo antes de commit. Verificar con conexión distinta que ambas filas aparecen juntas o ninguna; una excepción de test no prueba un crash de proceso.

Crash real: proceso hijo controlado alcanza barrera después de escritura antes de commit; terminar sólo ese PID, reiniciar y comprobar rollback. Otro caso confirma commit y bloquea respuesta antes de matar el proceso; reinicio demuestra hecho + auditoría completos y consulta autoritativa. No prometer rollback de secuencias: huecos de IDs son válidos.

Concurrencia determinista con dos conexiones y barreras, no sleeps como sincronización: apertura/apertura, cierre/cierre, egreso primero vs cierre primero, rollback del primer competidor y adquisición posterior. Verificar resultado, cardinalidad, actor, importe y bloqueo efectivo; retries de prueba no ocultan flakiness.

Browser/client real: sesión SID, requests hacia backend exacto y PostgreSQL aislada; abrir, egreso, cerrar, reiniciar backend y recargar; comprobar estado y auditoría por lectura DB. Registrar origen/destino sanitizados, build/revisión, escenario, salida, filas, artefactos y teardown de recursos propios. StoreCore permanece fixture/disabled; no se clasifica esa dependencia como live ni se usa reportes como evidencia de caja contable.
