# ADR-003 — Contexto POS verificado para nuevas intenciones

Estado: proposed; addendum de T08-B, revisión/GO específico pendiente. Fecha: 2026-10-07.
Base exacta: `39aa9d6dad199d88328e9c3799911e355a9cbddd`.
Complementa [ADR-002](ADR-002-sale-v2-lifecycle-command-journal.md); no hereda su aprobación ni modifica el contrato StoreCore.

## Brecha y decisión

StaffSession identifica autenticación (digest, usuario, CSRF y tiempos), no dispositivo. WorkspaceResponse sólo publica terminalId/cashierId/persistence, desde LocalDatabaseSeed. SaleV2RequestTranslator valida sintaxis y JdbcSaleMutationCommands verifica ownership y cuádruple existente, pero una nueva Reserve no vincula clientInstanceId/deviceId con instalación/terminal. SaleTicketComponent mantiene IDs fixture. La lectura de una operación existente sí publica identidad persistida, pero no resuelve la primera intención.

Fijar `PosExecutionContext(clientInstanceId, deviceId, terminalId)` obtenido de binding durable provisionado por servidor y configuración autorizada del conector. Resultado cerrado `Available(context) / Unavailable / Unknown`; Kotlin sealed/enum y TypeScript clase con constructor privado, casos cerrados y único fromWire en PosWireMapper. IDs son valores validados, no estados. Dominio sin HTTP/JDBC/UI. Un puerto consulta contexto, otro paso valida su correspondencia; el recorrido de admisión los ordena.

No derivar contexto de SID, CSRF, staff, storage, fingerprint de navegador, UUID aleatorio del browser ni última venta. No equiparar storecore_installation_ref con clientInstanceId porque coincidan fixtures. SID conserva autoridad exclusiva de actor/permisos; contexto no incluye actor, rol ni lifecycle y no es lease de autorización.

## Binding y provisión

Proponer `V11__pos_execution_context.sql`, verificando numeración contra base de integración. Tabla `pos_terminal_context`: terminal_id FK/PK, client_instance_id UUID, device_id VARCHAR(80) no vacío y UNIQUE(client_instance_id,device_id). Binding inmutable para runtime: app sólo SELECT, sin INSERT/UPDATE/DELETE; worker/auditor sin grants nuevos salvo necesidad demostrada. Constraints/triggers impiden modificación destructiva de bindings históricos. V1–V10 intactas.

Provisión explícita y auditada mediante operación administrativa fuera del browser, con terminal configurada existente, valores del conector autorizado y evidencia de instalación. No poblar automáticamente desde ventas, terminal_code, seed genérico ni UUID inventado. Fixture/harness provisiona valores explícitos en DB aislada; no habilita producción. Una instalación limpia sin provisión queda Unavailable.

Consulta y nueva admisión comprueban binding único de terminal configurada, terminal activa, UUID/device válidos y concordancia con clientInstanceId del conector usado por catálogo/reserva. No acceder DB StoreCore ni exponer credenciales/referencias secretas. Configuración del conector es inmutable durante vida del proceso; cambiarla exige reinicio compatible y conciliación de pendientes. Reemplazo de binding/dispositivo no es UPDATE runtime ni recuperación automática: requiere procedimiento separado, preservando historia. Este corte no implementa enrollment ni endpoint administrativo.

## Contrato HTTP

`GET /api/v2/pos/context`, permiso existente WorkspaceRead, SID actual, BaseResponse y Cache-Control: no-store. Rutas clasificadas explícitamente por StaffSessionFilter. CASHIER/SUPERVISOR/OWNER según política vigente; AUDITOR/Unknown no ganan WorkspaceRead. 401 sin sesión, 403 sin permiso, 200 con `{state:"Available",context:{clientInstanceId,deviceId,terminalId}}`; ausencia/incoherencia/DB indisponible 503 con estado Unavailable y context null. Esquema/estado desconocido se traduce a Unknown y bloquea. GET no provisiona, no inserta auditoría de éxito, no reclama comandos ni hace HTTP StoreCore.

No modificar workspace v1 ni sesión para este contrato. Endpoint read-only disponible aun en PreActivation/Paused; no habilita escritura ni filtra receipts. GET runtime lifecycle sigue separado con sus permisos.

## Validación transaccional y compatibilidad

En nueva Reserve v2: conservar fence lifecycle, serialización commandId/operationId, locks de agregados y autoridad actual; resolver replay autorizado antes de exigir binding actual. Después, un PosContextValidationStep verifica en la misma conexión/transacción que clientInstanceId/deviceId del payload coinciden exactamente con el contexto y que cashSession.terminalId es su terminal. Nunca corregir IDs silenciosamente antes del fingerprint. Mismatch conocido retorna Validation 400; contexto indisponible/incoherente retorna Unavailable 503, siempre después de permiso/visibilidad para no crear oráculo. No intención/outbox/recibo de éxito ante rechazo.

Terminal activa se revalida y protege frente a desactivación hasta commit. Documentar adquisición de su lock después de caja y antes de venta/delivery en todos los caminos que lo necesiten; ningún flujo administrativo puede adquirirlo antes de caja y luego esperar esa misma caja. Binding es inmutable; no introducir transacciones independientes ni invertir fence→command/operation→caja→terminal→venta→delivery. Probar las carreras antes de declarar garantía.

Replay previo autorizado retorna receipt original aunque cambió configuración/contexto, caja cerrada o lifecycle pausado, sin nueva escritura/dispatch. Payload/kind diferentes conservan mismatch sólo tras visibilidad. Commit/release/captura/reversión de operación existente utilizan su cuádruple persistida y autoridad actual; no exigir binding nuevo retroactivamente. GET históricos permanece read-only y visible según permisos, incluso para ventas v1 sin binding. No backfill de receipts ni adopción v2 ficticia. Writers v1 conservan fence de ADR-002; nueva consola no hace fallback.

## Frontend y alcance de dispositivo

T08-C obtiene sesión + contexto + lifecycle antes de nueva intención, valida los tres y congela IDs del contexto en Reserve. Puede generar commandId/saleId/operationId, que identifican intención, no instalación. Journal se particiona por origen real del browser + clientInstanceId/deviceId verificados, con terminal/caja/staff como referencias y evidencia. No guarda SID/CSRF. Contexto no válido, cambiado o respuesta de generación anterior bloquea; evidencia incompatible se conserva en cuarentena, nunca se borra para desbloquear.

Mismo staff/nuevo SID conserva ámbito y consulta con permisos actuales; otro staff no ve payload/receipt anterior, sólo marcador neutral del ámbito afectado. Contexto no sustituye autorización de backend. Recuperación histórica usa identidad de GET autoritativo sin reemplazarla por contexto actual. NotFound y pérdida de storage mantienen límites de ADR-002 y nunca autorizan POST automático.

deviceId identifica terminal POS lógica provisionada, no navegador físico autenticado. IndexedDB coordina pestañas del mismo origen/perfil; no garantiza exclusión entre perfiles, dispositivos ni borrado de datos. No prometer protección global contra duplicados de negocio con diferentes commandId. Identificar físicamente navegadores exigiría enrollment, credencial durable y contrato separado. Cambios de perfil/pérdida total requieren conciliación operativa antes de reemitir.

## Aceptación y cortes

- Tipos/mapper: casos conocidos y Unknown, UUID/device/terminal inválidos, envelope contradictorio, Unavailable neutral, sin strings de estado en vista/store.
- HTTP: SID/permisos actuales, AUDITOR denegado, no-store; GET sin escritura/dispatch; actor/role/lifecycle browser nunca autoridad.
- PG16: clean V1→V11, upgrade V10 poblada e historia intacta; provisión explícita, grants/constraints/inmutabilidad, fixture coherente con conector.
- Reserve: binding ausente, terminal inactiva, device/client distintos, caja de otra terminal y conector discrepante no dejan intención/outbox/receipt; autoridad antes del fallo observable.
- Concurrencia: contexto leído y terminal desactivada antes/durante admisión, ambos órdenes mediante dos conexiones/barreras; sin deadlock ni admisión fuera de contexto.
- Replay: mismo comando histórico aun sin binding actual; mismatch opaco; commit/release/pagos de operación previa sin reescribir identidad; history v1 no adoptada.
- UI/browser: nuevo SID conserva journal, actor diferente neutral, contexto cambiado/corrupto/tardío bloquea, reload/dos tabs, storage fallido y cero POST automático. Browser/PG16/crash mantienen gates propios de T09.

Orden: addendum SDD y revisión específica → CLR-T08-B-POS backend/schema/tests/reviews exact-head → T08-C sobre ese head → T09. T08-C queda `blocked_on_pos_context`; T08 parcial, nunca complete por este documento. Implementación, PG16, browser y review de este addendum NOT_RUN/PENDING. Sin activación, live, fiscal, homologación, publicación ni /sdd.finish.
