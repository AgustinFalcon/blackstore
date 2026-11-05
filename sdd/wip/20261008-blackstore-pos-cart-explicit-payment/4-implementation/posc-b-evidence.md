# Evidencia POSC-B — candidato local 2026-11-05

Base: `3a6a520555d499fceda835cf573f25921c922e5f`. Branch: `codex/blackstore-posc-b`. Estado: implementación local PASS; review/CI/merge pendientes.

## Entregado

- `POST /api/v3/sales/reservations` con allowlist anidada, 1–100 líneas, cantidades enteras, variantes únicas, moneda exacta y descuento cero.
- AdmissionV2 separado de V1: Unicode escalar/NUL, `trimV2`, orden UTF-8, golden independiente y selección cerrada de familia/versión/ruta.
- Una instantánea de catálogo autoritativa valida todas las líneas; intención, ticket, outbox, recibo y metadata se confirman o revierten juntos.
- V13 forward-only agrega identidad de catálogo por línea y metadata append-only; backfill sólo de recibos V10–V12 con procedencia V1 probada.
- GET v2/v3 filtra familia; replay cruza visibilidad, familia, ruta y fingerprint. Lectura reiniciada reconstruye variante, precio, versión y total por línea.
- Reserva v3 porta digest opaco interno y revalida SID/usuario/rol después de locks de caja y terminal. Logout anterior deja cero hechos; logout posterior al punto de admisión no deshace el commit.
- Fallos remotos sin referencias de línea permanecen `Unknown/RecoveryRequired`; no se inventa una línea afectada.

## Gates ejecutados

- `gradlew compileTestKotlin --no-daemon`: PASS.
- Contrato/HTTP/seguridad focal: PASS.
- `SaleAdmissionContractMigrationPostgresTest`: PG16 clean y upgrade 12→13 PASS.
- `SaleAdmissionPostgresTest`: atomicidad, familia, replay/restart, total 27.50 y carreras SID/terminal PASS.
- `gradlew test --no-daemon`: 74 suites, 311 tests, 0 failures, 0 errors, 0 skipped, 6m21s.
- `git diff --check`: PASS.

Docker Desktop y contenedores Testcontainers fueron detenidos al cerrar los gates. Esta evidencia no prueba StoreCore live, homologación, frontend, browser ni CI; corresponden a POSC-T/U/E.
