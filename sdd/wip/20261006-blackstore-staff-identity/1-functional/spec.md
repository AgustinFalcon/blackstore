# Especificación funcional

## Problema

BlackStore confía actualmente en actor y rol enviados por el navegador. Varias operaciones sensibles tampoco exigen identidad. Una interfaz oculta no constituye autorización.

## Requisitos

### SID-001 — Identidad propia

El operador inicia sesión con credenciales BlackStore. No hay registro público, federación con StoreCore ni autenticación por headers. Usuario inexistente, password incorrecta, cuenta inactiva o hash inválido producen un error genérico equivalente.

### SID-002 — Sesión confiable

El login crea una sesión opaca persistida. Recarga y reinicio conservan una sesión vigente. Expiración, revocación, cuenta inactiva o rol desconocido deniegan. Logout revoca servidor y cookie.

### SID-003 — Autoridad del servidor

Actor y rol provienen exclusivamente de la sesión y del usuario vigente en PostgreSQL. `X-Actor-Id`, `X-Role`, defaults visuales y campos adicionales no conceden autoridad.

### SID-004 — RBAC y ownership

- `CASHIER`: opera su propia caja y las ventas vinculadas a ella.
- `SUPERVISOR`: puede operar cajas ajenas según política explícita y con motivo cuando corresponda; accede a reportes.
- `OWNER`: operaciones y reportes habilitados por allowlist explícita.
- `AUDITOR`: sólo las lecturas expresamente enumeradas; ninguna mutación.
- `Unknown`: cero permisos.

La autorización sigue relaciones persistidas caja→venta→pago. Cero o múltiples asociaciones deniegan antes de efectos.

#### Matriz contractual de permisos

Errores: sin sesión válida=`401`; rol sin permiso=`403`; recurso inexistente o ajeno que no debe enumerarse=`404`. Un motivo obligatorio vacío=`400`. Las listas filtran filas no visibles en lugar de revelarlas como errores.

| Método y ruta | Frontera | Permiso cerrado | Roles y ownership | Motivo |
|---|---|---|---|---|
| `GET /api/v1/health` | pública, respuesta sanitizada | `PublicHealthRead` | cualquiera | no |
| `GET /api/v1/auth/csrf` | pública o sesión vigente | `CsrfBootstrap` | cualquiera | no |
| `POST /api/v1/auth/login` | pública + CSRF preauth | `StaffLogin` | anónimo sin cookie de sesión | no |
| `GET /api/v1/auth/session` | privada | `SessionRead` | todo staff activo | no |
| `POST /api/v1/auth/logout` | privada + CSRF | `StaffLogout` | todo staff activo | no |
| `GET /api/v1/catalog` | privada | `CatalogRead` | Cashier, Supervisor, Owner | no |
| `GET /api/v1/workspace` | privada | `WorkspaceRead` | Cashier propio; Supervisor/Owner | no |
| `GET /api/v1/cash-sessions` | privada | `CashSessionList` | Cashier sólo propias; Supervisor/Owner/Auditor visibles | no |
| `POST /api/v1/cash-sessions` | privada + CSRF | `CashSessionOpen` | Cashier para sí; Supervisor/Owner para usuario activo elegible | sí si asigna a otro |
| `POST /api/v1/cash-sessions/{id}/close` | privada + CSRF | `CashSessionClose` | Cashier propia; Supervisor/Owner cualquiera visible | sí si es ajena |
| `POST /api/v1/sales/reservations` | privada + CSRF | `SaleReserve` | Cashier caja propia; Supervisor/Owner caja visible | sí si es ajena |
| `GET /api/v1/sales/{operationId}` | privada | `SaleRead` | Cashier caja propia; Supervisor/Owner caja visible | no |
| `POST /api/v1/sales/{operationId}/commit` | privada + CSRF | `SaleCommit` | Cashier caja propia; Supervisor/Owner caja visible | sí si es ajena |
| `POST /api/v1/sales/{operationId}/release` | privada + CSRF | `SaleRelease` | Cashier caja propia; Supervisor/Owner caja visible | sí si es ajena |
| `POST /api/v1/payments` | privada + CSRF | `PaymentCapture` | Cashier caja propia; Supervisor/Owner caja visible | sí si es ajena |
| `POST /api/v1/payments/{id}/reversals` | privada + CSRF | `PaymentReverse` | Cashier caja propia; Supervisor/Owner caja visible | siempre; evidencia también obligatoria |
| `POST /api/v1/expenses` | privada + CSRF | `ExpenseRecord` | Cashier caja propia abierta; Supervisor/Owner caja visible abierta | sí si es ajena |
| `GET /api/v1/reports/shift` | privada | `ShiftReportRead` | Supervisor, Owner, Auditor | no |
| `GET /api/v1/reports/daily` | privada | `DailyReportRead` | Supervisor, Owner, Auditor | no |

La allowlist pública contiene exactamente health y los dos endpoints preauth (`csrf`, `login`). Todo endpoint nuevo es privado y sin permiso hasta agregar un caso cerrado, matriz y tests. `AUDITOR` no recibe catálogo, workspace, ventas ni mutaciones por inferencia.

### SID-005 — Navegación

`/sesion` ofrece login. Guards esperan el bootstrap de sesión antes de abrir rutas. El shell muestra identidad real y logout. `401` pasa a sesión anónima; `403` conserva sesión e informa permiso insuficiente. Respuestas tardías de otra generación de sesión se descartan y no se reintentan pagos automáticamente.

### SID-006 — CSRF y navegador

Toda mutación, incluidos login y logout, exige CSRF. La sesión usa cookie HttpOnly/SameSite; no se guarda token de sesión en storage. El origen preferido es frontend y API bajo el mismo site.

### SID-007 — Provisionamiento seguro

No existe password por defecto ni endpoint público de alta. Una herramienta local explícita crea o resetea staff sin imprimir secretos. La contraseña entra únicamente por prompt enmascarado o descriptor/stdin protegido explícito; argv, variable de entorno y archivo plano se rechazan. La herramienta exige confirmación de usuario/ID, transacción única y registra actor local, operación y resultado sin secreto. El usuario demo histórico conserva IDs/FK pero su credencial ficticia no queda utilizable.

### SID-008 — Evidencia y límites

La auditoría registra al actor confiable sin password, cookie ni token CSRF. Continúan un comercio, una VM y una PostgreSQL propia. Integraciones externas y `/sdd.finish` siguen NO-GO.

## Criterios de aceptación

1. Ningún endpoint comercial acepta autoridad desde headers o payload.
2. Todas las rutas privadas devuelven `401` sin sesión y `403` ante permiso insuficiente.
3. Usuario A no puede leer ni modificar caja, venta o pago de B fuera de la matriz aprobada.
4. Reiniciar backend no invalida una sesión vigente ni permite revivir una revocada.
5. Roles/estados desconocidos son tipos cerrados y deniegan.
6. Browser real demuestra cookie, CSRF, guard, logout y aislamiento entre sesiones.
