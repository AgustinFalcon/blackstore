# Especificación funcional

## Problema observado

La proyección de caja puede quedar abierta/cerrada sin su auditoría cuando falla la escritura siguiente. Un egreso autorizado antes del cierre puede persistirse después del cierre porque la autorización y la inserción no comparten el lock de caja. Persistencia habilitada requiere una decisión durable completa.

## Requisitos y aceptación

- **DCT-001 — Abrir atómicamente.** Una apertura válida crea una caja y exactamente su evento `CASH_SESSION_OPENED` con actor autenticado, propietario e importe exactos. Si falla cualquier escritura, ninguna queda confirmada. Dos aperturas concurrentes del mismo terminal producen una sola caja abierta y una sola auditoría de éxito; la perdedora responde conflicto sólo si la caja es visible, según el contrato HTTP inferior.
- **DCT-002 — Cerrar atómicamente.** Sólo una caja `OPEN` puede cerrar. Cierre, importe declarado, instante y evento `CASH_SESSION_CLOSED` se confirman juntos. Dos cierres no sobrescriben la primera declaración ni duplican su auditoría. Repetir el cierre devuelve conflicto; no se introduce una promesa de idempotencia por payload.
- **DCT-003 — Egreso vs cierre.** Egreso y cierre obtienen el mismo lock PostgreSQL de la sesión. El egreso revalida estado `OPEN`, actor, permiso y ownership dentro de esa transacción, e inserta egreso y auditoría de éxito juntos. Si egreso confirma primero, queda anterior al cierre; si cierre confirma primero, egreso se rechaza sin filas nuevas. Un fallo en la auditoría del egreso revierte el egreso. La respuesta usa su ID persistido, no un contador del proceso.
- **DCT-004 — Autoridad.** Cashier opera su propia caja; Supervisor/Owner pueden operar caja ajena sólo según la política existente y con motivo. Auditor, roles desconocidos, cuentas inactivas y cajas desconocidas/no elegibles fallan cerrado. Identidad proviene de staff autenticado; headers o IDs del body no sustituyen actor. La cuenta/rol y el propietario persistidos se revalidan en la frontera durable. Se mantiene el contrato de SID: revocación impide solicitudes nuevas, sin prometer cancelar una solicitud ya admitida.
- **DCT-005 — Dinero y vocabularios.** Apertura y declaración permiten cero y rechazan negativos; egreso requiere positivo. Todos respetan `MoneyPolicy` y `NUMERIC(14,2)`, sin redondeo silencioso ni `Double`. Métodos/estados/resultados finitos son tipos cerrados; valor wire desconocido llega a `Unknown` y bloquea escritura. Categoría y motivo siguen texto abierto validado, no un catálogo inventado.
- **DCT-006 — Fallos y reinicio.** Fallo DB nunca activa memoria. Crash antes de commit deja cero cambio parcial; crash después de commit antes de respuesta deja el cambio completo consultable tras reinicio. No se reenvía automáticamente un egreso cuya respuesta se perdió: sin clave de idempotencia no se promete exactamente una ejecución ante reintentos del cliente.
- **DCT-007 — Evidencia real.** Probar fallos inyectados, crash de proceso, concurrencia de conexiones independientes y reinicio sobre la misma PostgreSQL. Browser real autentica, abre, registra egreso, cierra, recarga tras reinicio y permite comprobar hechos persistidos. UI sola, memoria o mocks no cierran este gate.

## Interfaces y límites

Se conservan rutas y envelopes existentes de caja y `/api/v1/expenses`. El cambio respecto de SID es explícito y acotado: SID hoy convierte egreso sobre caja no abierta en `NOT_FOUND`; DCT separa visibilidad de elegibilidad y devuelve conflicto sólo después de autorizar visibilidad. Ventas/pagos y otros consumidores SID conservan su semántica.

- Ausencia, caja ajena a Cashier y estado persistido `Unknown` producen el mismo 404 `NOT_FOUND`, mensaje genérico y sin datos de caja. Caja ajena cerrada también es 404; no se divulga conflicto antes del filtro de visibilidad.
- Caja visible y actor permitido: cierre repetido o egreso tras cierre/reconciliación producen 409 `CASH_SESSION_CONFLICT`, sin mutación. Override de Supervisor/Owner requiere motivo; su ausencia produce 400 `VALIDATION`.
- Apertura concurrente: tras la violación del índice y rollback, consultar visibilidad en otra transacción. Caja bloqueante visible produce 409 `CASH_SESSION_CONFLICT`; ajena a Cashier o visibilidad no establecida produce 404 `NOT_FOUND` sin identificar propietario, caja ni estado. DB indisponible sigue 503, sin detalles.
- Autenticación/permiso siguen 401/403 y entrada inválida 400. Rechazos no generan auditoría de éxito ni exponen errores SQL/textos de excepción.

La UI traduce resultados en el borde a un tipo cerrado. Conflicto visible muestra etiqueta segura del tipo, bloquea el snapshot viejo y consulta cajas/contexto autoritativos sin repetir POST. No visible limpia selección y muestra texto genérico idéntico para ausente/ajena. `Unknown` bloquea con etiqueta neutral, sin imprimir `errorCode`/`message` crudos. Respuestas tardías no restauran selección de otro usuario tras logout/cambio de identidad.

No se calcula efectivo esperado, diferencia de arqueo, cierre contable, conciliación o fórmula turno/día. No se escribe `cash_ledger_events`, ni se arreglan reportes o pagos/ventas contra cierre en este corte. El éxito prueba sólo proyección/auditoría/egresos dentro de la frontera declarada. Esas capacidades requieren un siguiente SDD explícito. Tampoco integra StoreCore live, fiscal, MP, Correo Argentino, multiinstancia, despliegue ni release.
