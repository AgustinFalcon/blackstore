# Especificación técnica

## Dominio y puertos

El dominio no importa Spring, servlet, JDBC ni Angular. Usa tipos cerrados `StaffRole`, `StaffAccountState`, `StaffPermission`, `StaffSessionState`, `AuthenticationFailure` y `AuthorizationDecision`, todos con caso desconocido fail-closed donde corresponda. `StaffPermission` enumera: `PublicHealthRead`, `CsrfBootstrap`, `StaffLogin`, `SessionRead`, `StaffLogout`, `CatalogRead`, `WorkspaceRead`, `CashSessionList`, `CashSessionOpen`, `CashSessionClose`, `SaleReserve`, `SaleRead`, `SaleCommit`, `SaleRelease`, `PaymentCapture`, `PaymentReverse`, `ExpenseRecord`, `ShiftReportRead`, `DailyReportRead` y `Unknown`. Las reglas son allowlists positivas y exhaustivas.

Puertos: `StaffUserRepository`, `StaffSessionRepository`, `PasswordVerifier`, `SessionTokenGenerator`, `Clock`, `SecurityAuditPort` y consultas explícitas de ownership. Casos de uso: `LoginStaff`, `ResolveStaffSession`, `LogoutStaff`, `AuthorizeStaffAction` y `ProvisionLocalStaff`. El login se compone de pasos objeto: validar intento, verificar credencial, emitir sesión y registrar resultado.

## Persistencia y criptografía

- Nueva migración Flyway forward-only y transaccional; no editar V1–V3. Antes de aplicarla se exige backup verificable. Un fallo revierte la transacción y conserva V3; si el motor no permite revertir una sentencia, el runbook restaura backup antes de reintentar. No existe downgrade destructivo automático.
- Sesión: token aleatorio de 256 bits; PostgreSQL guarda sólo digest único, user FK, creación, último uso, expiración absoluta/inactividad y revocación.
- Duraciones iniciales: 30 minutos de inactividad y 12 horas absolutas, con `Clock` inyectable.
- Touch no resucita sesión revocada ni extiende el límite absoluto.
- BCrypt existente se conserva por adaptador; hash malformado deniega sin 500. Usuario inexistente ejecuta verificación ficticia y el rate limit no permite bloqueo permanente explotable.
- Runtime sin repositorio de identidad configurado queda cerrado; memoria sólo en tests.

## HTTP y browser

Contrato local:

- `GET /api/v1/auth/csrf`
- `POST /api/v1/auth/login`
- `GET /api/v1/auth/session`
- `POST /api/v1/auth/logout`

`GET /auth/csrf` responde `200 { csrfToken, expiresAt }`. En anónimo establece `blackstore-csrf-pre`, cookie opaca HttpOnly, Secure en producción, SameSite=Strict, Path=`/api/v1/auth`, sin Domain y máximo 5 minutos. El token viaja sólo en body y luego `X-CSRF-Token`; servidor guarda su digest ligado al precontexto. `POST /auth/login` acepta `{ login, password }`, exige cookie+header concordantes, consume el precontexto una sola vez y responde `200 { staff: { id, displayName, role }, csrfToken }` mientras establece la cookie de sesión. Una cookie de sesión ya presente jamás se reutiliza: sesión válida produce `409 ALREADY_AUTHENTICATED`; inválida se limpia y produce `401 SESSION_INVALID`, obligando a repetir bootstrap.

`GET /auth/session` responde `200 { staff: { id, displayName, role } }` o `401`; no devuelve token de sesión. `GET /auth/csrf` con sesión devuelve el token sincronizador de esa sesión. `POST /auth/logout` exige `X-CSRF-Token`, responde `204`, revoca sesión y expira cookies. Token preauth no sirve después de login; token de sesión no sirve después de logout/revocación ni en otra sesión. Todos los errores usan `{ code, message, correlationId }`, mensaje genérico para autenticación y `Cache-Control: no-store`.

Producción usa `__Host-blackstore-session` con `Secure`, `HttpOnly`, `SameSite=Lax`, `Path=/` y sin `Domain`. Desarrollo HTTP loopback usa perfil explícito y nombre distinto. Configuración insegura fuera de loopback falla al arrancar. Respuestas privadas llevan `Cache-Control: no-store`.

El token CSRF sincronizador está ligado a un precontexto para login y luego a la sesión. Rota al autenticar; el precontexto se consume atómicamente y no admite replay. Revocar/logout invalida el token de sesión. Precontextos vencidos se eliminan con job acotado y límite por origen. `SameSite` y Origin son defensa adicional, no sustituto. Las pruebas cubren replay preauth, token previo a login/logout, sesión preexistente, dos sesiones cruzadas y carrera logout/mutación.

Spring Security resuelve el principal en controllers privados/comerciales; health, csrf preauth y login son la única allowlist pública cerrada. Los casos de uso repiten autorización de negocio y ownership. Logout impide nuevas solicitudes después de revocar; no se promete cancelar una mutación ya admitida.

El rate limit se persiste en PostgreSQL para sobrevivir reinicios: login normalizado + origen confiable, 5 fallos/15 min con backoff exponencial y máximo 15 min; origen, 30 fallos/15 min. Éxito resetea el bucket de login. La dirección remota directa es la fuente; `Forwarded`/`X-Forwarded-For` sólo se acepta desde proxies configurados por allowlist. Los identificadores se guardan como digest. No hay bloqueo permanente ni mensaje que revele qué bucket actuó. Tests cubren límite, reset, reinicio, múltiples logins por origen y mismo login desde múltiples orígenes.

## Provisionamiento y recuperación

La herramienta administrativa se ejecuta con perfil separado y permisos DB mínimos específicos. Lee secreto por consola enmascarada o stdin/descriptor protegido, nunca argv/env/archivo; limpia buffers razonablemente y no loguea inputs. Alta/reset requieren confirmación del identificador y se ejecutan en una transacción. El evento administrativo registra actor del SO/instalación, target y resultado, no password/hash completo. El runbook exige backup previo, validación Flyway, prueba de login controlada y restauración de V3 ante fallo parcial no transaccional.

## Aplicación y atribución

Todos los controllers y comandos reciben `AuthenticatedStaff` confiable. Se eliminan headers de autoridad y defaults `cashierId`. `JdbcSaleRecordStore.createdBy`, audit, caja, egreso y reversa usan actor confiable y ownership persistido. La cuádruple StoreCore no cambia.

## Frontend

API relativa `/api/v1` y proxy dev. `SessionState` cerrado: Loading, Authenticated, Anonymous, Expired y Unknown/Unavailable. Mapper único en el borde. Store y componentes no comparan strings wire. Login/logout incrementan generación; respuestas tardías no restauran datos previos. Interceptor añade CSRF sólo al API propio; no contiene password, cookie ni rol literal.

## Seguridad y observabilidad

Eventos de auditoría son tipos cerrados y omiten material secreto. El rate limit cubre cuenta/origen. Logs nunca serializan requests de auth. El provisionamiento usa autoridad separada del runtime.

## NO-GO

Headers todavía autorizan; actor seed; sesión runtime en memoria; token/password en repo, logs o storage; Unknown habilita; mutación sin CSRF; endpoint privado olvidado; frontend guard con backend abierto; seed resetea cuentas al arrancar; migración rompe evidencia; fallback fixture de identidad.
