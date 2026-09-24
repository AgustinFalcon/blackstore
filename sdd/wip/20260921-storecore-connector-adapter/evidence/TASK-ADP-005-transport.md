# TASK-ADP-005 — Transporte local fail-closed

**Fecha:** 2026-09-23  
**Límite:** loopback/HttpServer. Sin host live, secreto real ni bean HTTP por defecto.

## Guard

`StoreCoreDispatchGuard` no despacha si `enabled=false`, kill-switch, capability inactiva, identity/token ref vacíos, digest incompatible, TLS requerido sobre HTTP, o plaintext fuera de loopback. `application.yml` queda `integration.enabled: false`, `mode: fixture`, `transport.kill-switch: true`, `tls-required: true`, refs vacíos.

## Headers y recovery

`StoreCoreHttpTransport` no es `@Component`. Bearer + `X-Client-Instance-Id`/`X-Device-Id`/`X-Sale-Id`/`X-Operation-Id`. POST incierto/lanzado hace GET del mismo cuádruple y no re-POST. HTTP 429 completado puede repetir el mismo POST (mismo cuádruple, mismo body), no un operation id nuevo. GET honra `Retry-After` acotado. Telemetría no incluye Bearer ni token.

## Validación

`gradlew test --tests com.blackstore.connector.StoreCoreHttpTransportTest --tests com.blackstore.architecture.ArchitectureBoundaryTest` OK.
