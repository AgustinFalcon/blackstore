# P2 exact-head — auditoría de denegación de autorización

## P2 Security exact-head — titular CASHIER estricto

Finding confirmado: usar permits(CashSessionOpen) para eligibleOwner admitía SUPERVISOR/OWNER como titulares, distinto de `JdbcStaffIdentity.eligibleCashier` anterior (active y role_code CASHIER). Corregido JDBC/memory: titular ACTIVE y rol exactamente StaffRole.CASHIER; permiso del actor se mantiene separado. Rechazos source Authorization, NotVisible/404 opaco y auditoría independiente única; no schema/grants nuevos.

Regresiones nuevas memory y HTTP/JDBC/PG: targets SUPERVISOR, OWNER, CASHIER inactivo y missing → una denegación, cero caja/success audit, mensaje genérico y procedencia exacta en memoria. HTTP positivos prueban SUPERVISOR/OWNER actuando sobre CASHIER ACTIVE con override (200), sin reducir permiso de actor.

Validación final source CASHIER estricto: enfocada22 PASS, BUILD SUCCESSFUL1m58; amplia nativa compatible153 PASS/39 suites/0 failures/errors/skips, BUILD SUCCESSFUL1m33. Mismas seis exclusiones Docker declaradas abajo; PG18 real, no claim PG16/browser/CI. Amplia151 queda antecedente previo, sustituida por153 post-fix. XML en work/dct-runtime/build/test-results/test. Teardown después de completion explícita: pg_ctl fast server stopped, pg_ctl status no server running, pg_isready5441 no response; main75632 y children78824/27488/45148/80348/74232/8216 ausentes, listener5441 ausente, launcher exit0. Sin backend/frontend/browser ni commit/push. Review del source corregido pendiente.

## Suite amplia FINAL sobre source posterior a corrección residual

2026-10-07: ejecutado nuevamente test amplio nativo compatible sobre source actual (incluye actor/owner sin autoridad), sin modificar producción. BUILD SUCCESSFUL 1m29. XML `work/dct-runtime/build/test-results/test`: 39 suites, **151 tests, 0 failures, 0 errors, 0 skipped**. Enfocada actual20 PASS. Amplia149 anterior queda como historia, reemplazada por esta validación151 del source final.

Mismas seis exclusiones Docker: StaffAuthHttpPostgresTest, BlackStoreSchemaMigrationTest, StaffIdentityMigrationTest, StaffIdentityPostgresTest, DurableSalePostgresTest, JdbcBlackStoreWriterTest. PostgreSQL18 loopback real; no claim PG16/suite Docker completa/browser/CI.

Teardown final después de completion explícita: cluster `work/dct-runtime/pgdata`, mainPID5020 detenido con pg_ctl fast (server stopped), pg_ctl status no server running, pg_isready5441 no response; main y children75924/56680/63724/49068/83848/85928 ausentes, listener5441 ausente. Launcher finalizado exit0. Sin backend/frontend/browser ni commit/push; sólo docs de validación actualizados.

## Extensión de procedencia — autoridad durable de actor/owner

Antes de congelar se confirmó una brecha residual: `AuthorizeStaffAction.open` anterior auditaba `eligibleCashier=false` con `deny(NOT_FOUND)`. Revalidar actor activo y owner activo/rol habilitado es autoridad, no validación de negocio. JDBC y memory ahora también marcan estos rechazos `Authorization`. No se amplía la marca a lookup inconcluso de blockers, dinero, categorías, método, longitud ni conflicto/DB indisponible. La restricción inicial sólo-policy se corrige explícitamente con esta evidencia.

Nuevas regresiones PG/application: actor durable desactivado y owner auditor inelegible devuelven Forbidden/NotVisible con procedencia Authorization, una sola AUTHORIZATION_DENIED para cada actor, cero success audits y cero caja nueva. Memory prueba los dos casos y exige que la caja esté vacía cuando se invoca el puerto independiente de auditoría. Enfocada del source nuevo: BUILD SUCCESSFUL 1m55, 20 tests/0 failures/errors/skips. Amplia149 corresponde al source previo a esta extensión, no se reclama como validación de source nuevo.

Teardown extensión PASS tras completion explícita: main PostgreSQL50428 apagado por pg_ctl fast (server stopped), pg_ctl status no server running, pg_isready5441 no response y main ausente. Launcher exit0. Seis crash children76764/27944/5728/63376/4072/78068 terminaron por test; ausencia final verificada. Sin backend/frontend/browser ni commit/push. Review del source final pendiente.

Fecha 2026-10-07. Se restaura `SecurityAuditEvent.AUTHORIZATION_DENIED` perdido al desplazar el precheck fuera de application. No se amplía schema, grants, ledger/reportes ni integración live.

`CashRejectionSource` es enum cerrado con default `Mutation`. JDBC y memory marcan `Authorization` al rechazar `CashMutationPolicy.authorize` o revalidar actor/owner sin autoridad, incluida visibilidad de blockers ocultos; propagación en excepción/resultado. Application registra una sola denegación mediante `AuthorizeStaffAction` después del rollback/retorno del adapter, fuera de la transacción rechazada, antes del mismo HTTP seguro. Validación común, Conflict y Unavailable no se marcan ni auditan como denegación.

Regresiones: unit matrix conserva todos los fallos y procedencia con igualdad exacta; resultados PG/memory existentes exigen la procedencia de autorización sin quitar asserts; MockMvc + PG comprueba caja ajena (open/close/expense) y override sin motivo con una fila independiente por rechazo. El filtro HTTP existente rechaza al auditor antes de application; una llamada directa a application prueba por separado permiso auditor, procedencia y auditoría PG. Exige cero denegaciones para actor propio ante conflictos y validación de método/importe, y conserva cardinalidad exacta de egreso/success audits y envelope 404/409.

Primera enfocada: 18 tests, 17 PASS/1 FAIL, expectation nueva de auditoría auditor vía HTTP incorrecta (filtro previo). DB confirmó cinco denegaciones de otro cajero y una de owner; no fallo productivo. Se conservó el assert de una auditoría y se agregó llamada directa de application, sin cambiar filtro ni quitar asserts HTTP. Repetición: BUILD SUCCESSFUL 1m25, XML 18 tests/0 failures/errors/skips. Amplia nativa compatible en curso.

Validación final: amplia nativa compatible BUILD SUCCESSFUL 1m28; XML en `work/dct-runtime/build/test-results/test`: 39 suites, 149 tests, 0 failures/errors/skips. Seis clases Docker existentes excluidas por entorno, no suite completa ni certificación PG16. Enfocada 18 PASS. `git diff --check` PASS (avisos CRLF únicamente).

Teardown PASS después de completion explícita del runner: Native PostgreSQL 18 aislada en `work/dct-runtime/pgdata`, loopback5441; main PID65748 detenido con `pg_ctl -D work/dct-runtime/pgdata -m fast stop`, salida server stopped; proceso ausente, `pg_ctl status` no server running, `pg_isready -h 127.0.0.1 -p 5441` no response y listener ausente. Proceso de arranque finalizado exit0. Crash children 73196/73280/83848/51588/75260/81828 terminados por tests; ausencia final de main y seis children verificada con Get-Process sin filas. Sin browser/backend/frontend nuevo. Mantener gates UI/browser/PG16/CI pendientes; review de este source corregido pendiente, no commit/push.
