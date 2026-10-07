# Aceptación browser DCT

El job separado `dct-browser` ejecuta Chromium Playwright contra Angular real y un proceso `java -jar`, con PostgreSQL `postgres:16-alpine` efímero. No usa mocks de seguridad, interceptación de respuestas ni endpoints debug. La fixture configura persistencia, identidad loopback y origin explícitamente; no activa `application-local`.

## Ejecución

Requisitos: Docker accesible, Java17, Node compatible con package.json y puertos 8081/4201 libres. La fixture rechaza puertos ocupados sin detener sus propietarios.

```sh
cd backend
./gradlew bootJar --no-daemon --max-workers=2
cd ../frontend
npm ci
npx playwright install --with-deps chromium
npm run typecheck:dct
npm run test:dct
```

Windows: usar gradlew.bat; `DCT_JAVA` permite elegir Java y `PLAYWRIGHT_BROWSERS_PATH` una carpeta propia. No hay URL DB externa ni fallback PG18/memory. Cada test adquiere un container propio, loopback/puerto efímero, cuyo ID exacto retiene para teardown. El bootstrap del container aplica Flyway/seed; comandos DCT usan los roles runtime existentes. No certifica separación de credenciales de instalación/productivas.

Seed exclusivo: CASHIER propio/ajeno, OWNER y AUDITOR con bcrypt/passwords aleatorias; cajero histórico inactivo. Credenciales se pasan por entorno, nunca por argv/logs. Scheduler de ventas deshabilitado sólo en fixture y tests HTTP PG pertinentes.

## Escenarios

`cash-browser-restart`: login formulario, apertura 100.00, egreso 7.25, cierre 92.75; verifica caja/owner/terminal/fechas, único egreso con actor/método/importes exactos y tres eventos. Reinicia sólo Java, exige PID distinto y conserva frontend/DB. Recarga observa SID/GET exitosos, closed por PosWireMapper, ningún POST de replay, hechos idénticos y ausencia de controles de cierre/egreso. La UI excluye cerradas de selección: no exige tarjeta histórica.

`denials`: fetch dentro de Chromium con cookies HttpOnly y CSRF reales; anónimo401, CSRF403, ajeno/ausente404 opaco, OWNER sin motivo400, método Unknown400, AUDITOR403 y propia cerrada409. Denegaciones dejan intactos caja/egresos/éxitos; autoridad crea auditoría independiente. Dos contextos del mismo cajero producen UI anterior: uno cierra, otro intenta egreso→409, luego GET sin replay POST. Recarga mantiene cero duplicados.

Estados/métodos/roles/outcomes reutilizan tipos cerrados y mappers; eventos DB se traducen en DctAuditEvent con Unknown. Fixture/base/login/caja tienen responsabilidades separadas.

## Evidencia y teardown

JSON en `frontend/work/dct-browser-results/results.json`, ignorado; CI publica sólo ese JSON por SHA durante siete días. Trace/video/screenshot apagados. No publicar cookies/CSRF/passwords/dumps staff_sessions. Logs registran owner/revisión/IDs/PIDs; finally detiene árboles propios y remueve sólo el container adquirido. Reinicio espera exit. SIGKILL del job puede impedir finally; runner hospedado desechable limita ese residual, no copiar teardown global a máquinas compartidas.

Run local 2026-10-07 BLOCKED antes de assertions por permisos Docker. Discovery/typechecks no cierran aceptación; PG16/browser y el job sobre este source pendientes hasta ejecución completa y review del SHA exacto. Ver evidencia DCT-browser-harness-20261007.md del WIP.
