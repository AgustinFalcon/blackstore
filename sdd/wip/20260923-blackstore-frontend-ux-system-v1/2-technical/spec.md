# Technical Spec — BlackStore frontend UX

**Status:** `ready_for_sol_review`

## Stack

Angular 22 consola existente (`frontend/`). BSUX-ANG aplicó tokens/estados en las 5 rutas existentes; no es pixel-complete. Containers/stores del piloto no se reescriben en este PR.

## Contratos de UI

- Envelope `BaseResponse` (`code,data,errorCode,retryable,message,traceId`). Nunca parsear `message`.
- Health: `storeCoreIntegrationEnabled=false` hasta GO del adapter.
- Persistencia local perfil `local` puerto 5433. Tests en memoria.
- Identidad: USER BlackStore. No reutilizar guards CUSTOMER de StoreCore.

## Fail-closed

- Entitlement DISABLED/mismatch: writes disabled, banner, sin llamada StoreCore.
- Catálogo stale / StoreCore unavailable (simulador): bloquea transición de venta nueva.
- Fiscal NOT_CONFIGURED: sólo test/dev; producción no emite.

## Relación StoreCore

Pointer Markdown al OpenAPI `1.0.0-draft`. Este frontend no llama `/blackstore-integration/v1`.
