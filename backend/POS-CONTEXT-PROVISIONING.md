# Provisión del contexto POS

V11 crea el binding vacío. El runtime nunca provisiona ni reconstruye identidades desde ventas, staff, SID, terminal_code o seeds. Una instalación limpia devuelve Unavailable hasta que un administrador autorizado registre valores y evidencia de instalación del conector. No hay endpoint de enrollment.

Configurar explícitamente `blackstore.pos.terminal-id` con una terminal existente y `blackstore.storecore.transport.client-instance-id` con el UUID canónico autorizado del conector usado por catálogo/reserva. Ambos valores se capturan al iniciar el proceso; un cambio requiere reinicio compatible y conciliación de intenciones pendientes. El valor por defecto de terminal es 0 y el del conector vacío: fallan cerrados. No usar la referencia de companion como sustituto del UUID del conector.

El administrador de la DB BlackStore ejecuta una transacción de provisión con valores revisados y parámetros SQL; nunca el rol blackstore_app. Ejemplo de operación parametrizada (los símbolos son parámetros obligatorios, no valores fixture):

```sql
BEGIN;
SELECT id FROM terminals WHERE id=:terminal_id AND active FOR SHARE;
-- Verificar exactamente una fila y evidencia de instalación antes del INSERT.
INSERT INTO pos_terminal_context
 (terminal_id,client_instance_id,device_id,provisioned_by_ref,installation_evidence_ref)
VALUES (:terminal_id,:authorized_connector_client_uuid,:provisioned_device_id,
        :administrative_approval_ref,:installation_evidence_ref);
COMMIT;
```

El binding tiene PK/FK de terminal y unicidad client/device, identidad válida, referencias administrativas/evidencia obligatorias y timestamp DB. UPDATE, DELETE y TRUNCATE están prohibidos por triggers incluso con privilegios administrativos ordinarios; el rol app sólo SELECT. Ni worker ni auditor reciben grants sobre el binding. Reemplazar un dispositivo requiere un procedimiento separado que preserve la historia; este corte no permite editar el binding para resolver una incidencia.

En Reserve nuevo, la conexión física mantiene fence lifecycle → serialización command/operation → caja → terminal FOR SHARE → venta → delivery hasta commit. El lock de terminal utiliza una función definer estrecha con search_path fijo y nombre de tabla calificado; app no recibe UPDATE sobre terminals. Desactivar terminal con UPDATE espera al lock de admisión; si la desactivación ganó, Reserve revalida active y rechaza. Una observación GET anterior no es un lease de autorización.

Todo procedimiento administrativo futuro que también espere una caja debe adquirir caja antes de terminal. Desactivar sólo terminal no espera caja. No añadir un camino terminal→caja. Otros comandos históricos, captura/reversión/commit/release y consultas no requieren contexto nuevo. Replay se autoriza por actor/permiso/ownership actuales antes de cualquier contexto/lifecycle/caja abiertos, y retorna el recibo original sin nueva intención ni dispatch.

GET /api/v2/pos/context conserva SID + WorkspaceRead y no-store; query JDBC read-only revalida staff activo/rol actual. Available incluye únicamente clientInstanceId/deviceId/terminalId. Ausencia, incoherencia, terminal inactiva, conector discrepante o SQL indisponible retornan 503 Unavailable sin identidad cruda. AUDITOR/Unknown no obtienen WorkspaceRead. No hay HTTP StoreCore, auditoría de éxito ni provisión en GET.

Fixtures de SaleAdmissionPostgresTest provisionan explícitamente valores identificados como isolated-fixture en una DB descartable. No habilitan producción. PG16 real, carreras, clean/upgrade y browser conservan gates propios; consultar evidencia del corte antes de afirmar garantías ejecutadas.
