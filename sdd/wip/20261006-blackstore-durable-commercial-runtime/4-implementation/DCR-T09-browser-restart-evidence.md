# DCR-T09 — evidencia de navegador, persistencia y reinicio

Fecha: 2026-10-06  
Entorno: frontend local `4201`, backend local `8081`, PostgreSQL local `5433`.  
Integraciones externas: deshabilitadas; no se ejecutó homologación fiscal, Mercado Pago, Correo Argentino ni StoreCore live.

## Escenario aceptado

1. Inicio de sesión staff con identidad persistida y sesión de caja abierta.
2. Catálogo visible con cinco artículos.
3. Alta de venta desde navegador: `RESERVE` respondió `PENDING` y el frontend esperó el resultado durable mediante `GET` correlacionados y acotados, sin repetir el comando.
4. La reserva alcanzó `RESERVED` con evidencia válida; recién entonces se capturó el pago.
5. Operación final: `68cd504c-e0e2-4fd9-b450-37c1e6733e7f`, estado `COMMITTED`, cobertura `PAID`.
6. `/ventas` listó y reabrió la operación mostrando caja `1`, cajero responsable `3`, actor histórico `2`, comprobante, referencia de reserva y pago capturado `2`.
7. Antes y después de abrir el detalle, la base conservó `state=COMMITTED`, `version=4`, `commands=2`, `payments=1`, `applied=2`: la reapertura es de sólo lectura y no dispara comandos.
8. Se reinició el backend; sesión, caja y venta siguieron persistidas y `/ventas` volvió a listar la operación confirmada.

## Recuperación cerrada

Una operación de prueba deliberadamente interrumpida durante la validación de permisos de V6 fue recuperada como `RECONCILIATION_REQUIRED`. El runtime no inventó éxito ni repitió cobros; conservó el caso para intervención explícita.

## Hallazgos corregidos durante aceptación

- La respuesta asíncrona de reserva ya no se interpreta como fallo: `AwaitReservationStep` hace únicamente lecturas correlacionadas, con límite temporal y sin reintentar escrituras.
- El flujo completo se cancela al cambiar la sesión o destruir el componente; una sesión nueva no puede continuar el timer, las lecturas ni el cobro de un intento anterior.
- El motivo de autorización excepcional acompaña al pago y queda en `audit_events`, dentro de la misma transacción durable, junto con el actor confiable.
- CORS usa el origen exacto configurado, acepta credenciales y permite sólo el preflight público explícito; los `POST` conservan sesión, RBAC, `Origin` y CSRF.
- V7 concede al rol runtime sólo `SELECT` sobre la guarda fiscal necesaria para que el trigger pueda validar, sin permisos de modificación ni bypass.

## Validaciones locales

- Backend: `153 tests`, `0 failures`, `0 errors`, `0 skipped`, `BUILD SUCCESSFUL`.
- Frontend: `npm run typecheck` exitoso.
- Frontend: `npm run build` exitoso sobre el mismo código luego del arreglo de polling; repeticiones posteriores quedaron afectadas por el ACL del sandbox al recorrer `C:\`, no por diagnóstico del compilador.
- Navegador y reinicio: escenario completo aprobado según la evidencia anterior.
- Review de bugs GPT-6.1 Sol: `APPROVED`, sin P0–P3.
- Review de seguridad GPT-6.1 Sol: `APPROVED`, sin P0–P3.
- Review SDD/arquitectura GPT-6.1 Sol: `APPROVED`, sin P0–P3; DCR-T09 continúa pendiente hasta que GitHub CI ejecute Karma y las verificaciones del backend.

La evidencia de CI de GitHub y la revisión SDD final se agregan al progreso antes de cerrar DCR-T09.
