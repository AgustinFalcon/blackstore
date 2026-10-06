# SID-T09 — aceptación browser real

Fecha: 2026-10-06. Base validada: `f04c7d6af1bc99122187d612f4c0c3d7773091b1` más las correcciones finales documentadas en este mismo commit.

## Topología

- Angular real en `http://localhost:4201`, con `proxy.conf.json` y sin `route.fulfill`.
- Spring Boot real en `127.0.0.1:8081`, perfil `local`.
- PostgreSQL 18 efímero en `127.0.0.1:5433`, esquema Flyway V1–V5.
- StoreCore live deliberadamente deshabilitado; se conservó el banner fail-closed de simulador local.

## Recorrido observado

1. Un deep link anónimo a `/reportes` terminó en `/sesion`.
2. `owner-demo` inició sesión; el shell mostró identidad y rol reales.
3. Owner abrió la caja `1` para el cashier `4`, terminal `1`, apertura `1000`, con motivo explícito. La UI recargó la proyección PostgreSQL.
4. Logout devolvió a `/sesion`. PostgreSQL registró cero sesiones owner activas y una revocada.
5. `cashier-demo` inició una sesión distinta y vio su caja propia abierta.
6. El cashier no vio Reportes en navegación ni en accesos rápidos. Un intento previo por acceso rápido fue interceptado por el guard, volvió a `/` y mostró `Permiso insuficiente para esta operación`; el acceso rápido se corrigió en este commit.
7. Con sesión autenticada, `document.cookie` fue vacío: la cookie de sesión no fue accesible desde JavaScript.

## Evidencia persistida

- `cash_session_projection`: caja `1`, terminal `1`, cashier `4`, estado `OPEN`, apertura `1000.00`.
- `audit_events`: `LOGIN_SUCCEEDED` owner; `CASH_SESSION_OPENED` con actor `3` y motivo `Apertura de aceptación browser SID-006`; `LOGOUT` owner; `LOGIN_SUCCEEDED` cashier.
- `staff_sessions`: owner `0` activas/`1` revocada; cashier `1` activa/`0` revocadas al momento de la comprobación.

Las credenciales y la base fueron exclusivamente efímeras para aceptación local. No constituyen seed productivo ni homologación externa.
