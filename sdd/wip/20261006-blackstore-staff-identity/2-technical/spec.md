# Especificación técnica

## Dominio y puertos

El dominio no importa Spring, servlet, JDBC ni Angular. Usa tipos cerrados `StaffRole`, `StaffAccountState`, `StaffPermission`, `StaffSessionState`, `AuthenticationFailure` y `AuthorizationDecision`, todos con caso desconocido fail-closed donde corresponda. Las reglas son allowlists positivas y exhaustivas.

Puertos: `StaffUserRepository`, `StaffSessionRepository`, `PasswordVerifier`, `SessionTokenGenerator`, `Clock`, `SecurityAuditPort` y consultas explícitas de ownership. Casos de uso: `LoginStaff`, `ResolveStaffSession`, `LogoutStaff`, `AuthorizeStaffAction` y `ProvisionLocalStaff`. El login se compone de pasos objeto: validar intento, verificar credencial, emitir sesión y registrar resultado.

## Persistencia y criptografía

- Nueva migración Flyway; no editar V1–V3.
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

Producción usa `__Host-blackstore-session` con `Secure`, `HttpOnly`, `SameSite=Lax`, `Path=/` y sin `Domain`. Desarrollo HTTP loopback usa perfil explícito y nombre distinto. Configuración insegura fuera de loopback falla al arrancar. Respuestas privadas llevan `Cache-Control: no-store`.

El token CSRF sincronizador está ligado a un precontexto para login y luego a la sesión. Rota al autenticar, expira y tiene limpieza acotada. `SameSite` y Origin son defensa adicional, no sustituto.

Spring Security resuelve el principal; los casos de uso repiten autorización de negocio y ownership. Logout impide nuevas solicitudes después de revocar; no se promete cancelar una mutación ya admitida.

## Aplicación y atribución

Todos los controllers y comandos reciben `AuthenticatedStaff` confiable. Se eliminan headers de autoridad y defaults `cashierId`. `JdbcSaleRecordStore.createdBy`, audit, caja, egreso y reversa usan actor confiable y ownership persistido. La cuádruple StoreCore no cambia.

## Frontend

API relativa `/api/v1` y proxy dev. `SessionState` cerrado: Loading, Authenticated, Anonymous, Expired y Unknown/Unavailable. Mapper único en el borde. Store y componentes no comparan strings wire. Login/logout incrementan generación; respuestas tardías no restauran datos previos. Interceptor añade CSRF sólo al API propio; no contiene password, cookie ni rol literal.

## Seguridad y observabilidad

Eventos de auditoría son tipos cerrados y omiten material secreto. El rate limit cubre cuenta/origen. Logs nunca serializan requests de auth. El provisionamiento usa autoridad separada del runtime.

## NO-GO

Headers todavía autorizan; actor seed; sesión runtime en memoria; token/password en repo, logs o storage; Unknown habilita; mutación sin CSRF; endpoint privado olvidado; frontend guard con backend abierto; seed resetea cuentas al arrancar; migración rompe evidencia; fallback fixture de identidad.
