# Evidencia POSC-B — candidato local 2026-11-05

Base: `3a6a520555d499fceda835cf573f25921c922e5f`. Branch: `codex/blackstore-posc-b`. Estado: implementación local PASS; review/CI/merge pendientes.

## Entregado

- `POST /api/v3/sales/reservations` con allowlist anidada, 1–100 líneas, cantidades enteras, variantes únicas, moneda exacta y descuento cero.
- AdmissionV2 separado de V1: Unicode escalar/NUL, `trimV2`, orden UTF-8, golden independiente y selección cerrada de familia/versión/ruta.
- Una instantánea de catálogo autoritativa valida todas las líneas; intención, ticket, outbox, recibo y metadata se confirman o revierten juntos.
- V13 forward-only agrega identidad de catálogo por línea y metadata append-only; backfill sólo de recibos V10–V12 con procedencia V1 probada.
- GET v2/v3 filtra familia; replay cruza visibilidad, familia, ruta y fingerprint. Lectura reiniciada reconstruye variante, precio, versión y total por línea.
- Reserva v3 porta digest opaco interno y revalida SID/usuario/rol después de locks de caja y terminal. Logout anterior deja cero hechos; logout posterior al punto de admisión no deshace el commit.
- La vista de catálogo V2 conserva U+00A0 y whitespace Unicode fuera de `trimV2` sin cambiar el comportamiento legacy V1; la persistencia PG16 prueba los bytes crudos de variante, SKU, nombre y versión.
- Rechazos locales devuelven referencias y razones de tipo cerrado para todas las líneas inválidas de la instantánea, sin persistir una intención parcial.
- El recorrido HTTP v3 compuesto ejecuta filtro real, cookie SID, CSRF, autorización de rol y controlador; SID ausente, CSRF inválido y AUDITOR/Unknown no alcanzan el puerto.
- Un proceso hijo real se termina antes y después del commit de admisión. El reconocimiento del worker repite el mismo límite con una venta multiline A×2+B×1, total 27.50, dos versiones y aplicación/replay únicos.
- Fallos remotos sin referencias de línea permanecen `Unknown/RecoveryRequired`; no se inventa una línea afectada.

## Gates ejecutados

- `gradlew compileTestKotlin --no-daemon`: PASS.
- Contrato/HTTP/seguridad focal: PASS; incluye composición filtro→controlador, allowlist, familia v3 y códigos cerrados por línea.
- `SaleAdmissionContractMigrationPostgresTest`: PG16 clean y upgrade 12→13 PASS.
- `SaleAdmissionPostgresTest`: atomicidad, familia, replay/restart, total 27.50, Unicode V2, matriz A06, carreras SID/usuario/rol/terminal y crash de PID propio PASS.
- `AccountingMutationPostgresTest.workerRecognitionChildCrashIsAtomicBeforeAndAfterCommit`: venta multiline, efecto remoto ya disponible, commit local antes/después del crash, total/versiones/líneas y reconocimiento único PASS.
- `gradlew test --no-daemon`: 74 suites, 319 tests, 0 failures, 0 errors, 0 skipped, 7m07s.
- `git diff --check`: PASS.

Docker Desktop y contenedores Testcontainers fueron detenidos al cerrar los gates. Esta evidencia no prueba StoreCore live, homologación, frontend, browser ni CI; corresponden a POSC-T/U/E.
