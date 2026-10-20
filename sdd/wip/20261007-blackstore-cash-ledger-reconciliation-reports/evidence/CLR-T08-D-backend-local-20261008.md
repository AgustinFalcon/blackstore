# CLR-T08-D backend — local evidence

Base exacta: `e0608e2bf3f8b7fb34454d8105f973f261fe3ba9`. Branch `feat/cash-ledger-evidence-backend`.
GO de implementación explícito recibido para este corte; no se heredan approvals ni aceptación runtime.

## Implementation

DurableSalePaymentResponse propaga originalPaymentId nullable desde PaymentLedgerEntry. La política PaymentTransitionPolicy existente conserva la única autoridad refund→capture; no se duplican reglas en HTTP.

GET `/api/v2/expenses/commands/{commandId}/projection` tiene permiso AccountingCommandRead explícito. Query y servicio separados del puerto de mutación. Una conexión REPEATABLE READ read-only con rol blackstore_app revalida SID activo/no revocado/no expirado, actor ACTIVE, permisos actuales AccountingCommandRead/ExpenseRecord y ownership con AccountingReceiptAccessPolicy (incluye caja cerrada). No usa locks de writer, writes, backfill, nuevo permiso, HTTP externo ni lifecycle admission.

Found correlaciona receipt/gasto/liquidación/postings exactos. SettleExisting verifica también receipt y devengo previos sin atribuirlos al nuevo comando. Un Accrue permanece impago en su proyección aunque una liquidación posterior exista. Postings se cargan sin joins de fan-out. Fuente ausente/contradictoria/legacy/Unknown queda Unavailable; ausente/no visible/kind distinto queda NotFound opaco. Todos los estados se traducen en un borde HTTP con no-store y mensajes seguros. Snapshot publica cutoff/asOf/id, versión y completitud del comando, nunca del período o caja.

No migración: V1→V11 existente alcanza; grants runtime vigentes y rol blackstore_app efectivo se ejercitan en pruebas JDBC.

## Local verification scope

Windows / PostgreSQL 18.0 aislada en loopback `127.0.0.1:55483`, database `t08d`, data `work/pg18-t08d-data`. Se usan credenciales efímeras locales de fixture, nunca servicio real.
JDK 21.0.12 instalado mediante init script local externo; código mantiene toolchain canónica JDK17. Gradle offline con compiler in-process para evitar daemon Kotlin fuera de writable roots.

Pruebas nuevas: policy de devengo/fuentes/IDs/duplicados/mismatch/liquidación íntegra; controller envelopes/401/403/400/no-store/referencia pago nullable; clasificación de ruta; JDBC tres operaciones, caja cerrada/Paused, actor revocado/rol auditor/caja ajena, receipt corrupto sin backfill, fuentes originales intactas y barrera determinista snapshot antes de commit de liquidación.

Primer focalizado unit/controller/SID/payment PASS. Primer JDBC encontró omisión de datos de cierre en construcción de CashSession y la corrección quedó cubierta por rerun; la carrera determinista pasó en ese intento.

Rerun focalizado: 35 tests / 6 suites / 0 fallos (8 arquitectura, 3 policy, 14 payment policy existente, 5 SID, 2 JDBC PostgreSQL18, 3 controller). Tras endurecer ownership antes de materializar caja ajena, rerun JDBC final: 2/2 PASS.

Fixtures de regresión corregidos sin relajar producción: AccountingReportPostgresTest genera BCrypt de fixture válido; JdbcBlackStoreWriterTest da un operationId propio a la venta secundaria y conserva la cuádruple primaria inequívoca. El segundo agrega modo opt-in BLACKSTORE_TEST_JDBC_URL, conservando PostgreSQL16 Testcontainers como default. AccountingReportPostgresTest rerun PostgreSQL18 PASS; primer intento al cambiar de DB aislada encontró roles ya existentes del fixture anterior, se eliminaron sólo DB/roles del cluster propio y el rerun pasó.

JdbcBlackStoreWriterTest rerun PostgreSQL18 PASS (1/1). Server aislado detenido con pg_ctl fast/wait: `server stopped`; puerto 55483 sin respuesta. Cluster de fixture conservado detenido: revisión automática rechazó su borrado recursivo. Ningún PID propio sigue sirviendo PostgreSQL. XML sanitizados de ambos fixtures conservados fuera del source en `work/t08d-accounting-report-pg18.xml` y `work/t08d-jdbc-writer-pg18.xml`.

Primer intento de regresión local: 211 PASS y 2 inicializaciones Docker bloqueadas (StaffIdentityMigrationTest/BlackStoreSchemaMigrationTest). Se conserva el bloqueo y se excluyen junto con las otras clases PostgreSQL Docker-only para la regresión local disponible; no se afirma suite PG16 completa.

Regresión local final disponible: **211 tests / 57 suites / 0 failures / 0 errors / 0 skips**, BUILD SUCCESSFUL. Exclusiones explícitas: `*PostgresTest`, JdbcBlackStoreWriterTest, StaffIdentityMigrationTest y BlackStoreSchemaMigrationTest; los dos fixtures y los dos escenarios JDBC nuevos se ejecutaron aparte sobre PG18 como se detalla arriba. Init scripts locales externos `blackstore-t08b-jdk21-local.gradle` y `blackstore-t08d-local-tests.gradle`; `git diff --check` PASS. Es una regresión local disponible, no una declaración de suite Docker/PG16 completa.

PG16 BLOCKED/NOT_RUN: Docker devolvió permiso denegado al pipe docker_engine; sólo PostgreSQL18 está instalada localmente. PostgreSQL18 no acredita PG16. Browser/reinicio/CI/reviews exact-head NOT_RUN en este corte. Sin push/PR/deploy/activación/homologación ni marcado done.
