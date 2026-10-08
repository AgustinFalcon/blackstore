# Aceptación browser DCT

El job separado `dct-browser` ejecuta Chromium Playwright contra Angular real y un proceso `java -jar`, con PostgreSQL `postgres:16-alpine` efímero. No usa mocks de seguridad, interceptación de respuestas ni endpoints debug. La fixture configura persistencia, identidad loopback y origin explícitamente; no activa `application-local`. El contrato actual es contable v2 y schema V11.

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

Antes de levantar Angular, un primer JAR aplica Flyway/seed y se detiene. La conexión administrativa del container propio crea una terminal aislada, provisiona `pos_terminal_context` con UUID del conector/device y referencias explícitas de fixture, y activa `accounting_runtime` en una transacción. Un nuevo JAR recibe `BLACKSTORE_POS_TERMINAL_ID` y `BLACKSTORE_STORECORE_TRANSPORT_CLIENT_INSTANCE_ID` coherentes. La fixture exige V11 y el binding exacto; ningún browser decide o provisiona identidad. La activación sólo existe en esta DB descartable, nunca en una instalación externa.

## Escenarios

`cash-browser-restart`: login formulario, POST v2 apertura 100.00, egreso 7.25, cierre 92.75; verifica caja/owner/terminal/fechas, egreso y settlement únicos, tres receipts/auditorías, postings OPENING/EXPENSE_ACCRUAL/EXPENSE_PAID correlacionados por commandId y arqueo Balanced con esperado 92.75/diferencia 0. El journal IndexedDB real contiene tres intenciones Resolved del actor/contexto y los mismos commandIds que PostgreSQL. Reinicia sólo Java, exige PID distinto y conserva frontend/DB. Recarga observa SID/GET exitosos, ningún POST v1/v2 de replay, hechos/journal idénticos y ausencia de controles de cierre/egreso. La UI excluye cerradas de selección: no exige tarjeta histórica.

`denials`: fetch v2 dentro de Chromium con cookies HttpOnly y CSRF reales; anónimo401, CSRF403, ajeno/ausente404 opaco, OWNER sin motivo400, método Unknown400, AUDITOR403 y propia cerrada409. Denegaciones dejan intactos receipts/postings/settlements/caja/egresos; autoridad crea auditoría independiente. Dos contextos del mismo cajero producen UI anterior: uno cierra, otro intenta egreso y el GET de referencias actual comprueba CLOSED antes de preparar journal/POST; conserva bloqueo sin enviar. Recarga mantiene cero POST/duplicados. ACTIVE rechaza writer v1 con LegacyDisabled y PAUSED rechaza v2 con Paused; no se reactiva legacy.

Estados/métodos/roles/outcomes reutilizan tipos cerrados y mappers; eventos DB se traducen en DctAuditEvent con Unknown. Fixture/base/login/caja tienen responsabilidades separadas.

El helper de mutaciones captura status/commandId del POST y exige receipt Committed correlacionado por GET v2 read-only. Consume el JSON del GET en Chromium con SID real; evita `response.json()` tardío de Playwright/CDP (CI #45: Network.getResponseBody No data found). También conserva validación de UI y hechos PostgreSQL. No repite POST ni convierte un fallo de lectura en éxito.

## Evidencia y teardown

JSON en `frontend/work/dct-browser-results/results.json`, ignorado; CI publica sólo ese JSON por SHA durante siete días. Trace/video/screenshot apagados. No publicar cookies/CSRF/passwords/dumps staff_sessions. Logs registran owner/revisión/IDs/PIDs; finally detiene árboles propios y remueve sólo el container adquirido. Reinicio espera exit. SIGKILL del job puede impedir finally; runner hospedado desechable limita ese residual, no copiar teardown global a máquinas compartidas.

Run local 2026-10-07 BLOCKED antes de assertions por permisos Docker. Discovery/typechecks no cierran aceptación; PG16/browser y el job sobre este source pendientes hasta ejecución completa y review del SHA exacto. Ver evidencia DCT-browser-harness-20261007.md del WIP.
