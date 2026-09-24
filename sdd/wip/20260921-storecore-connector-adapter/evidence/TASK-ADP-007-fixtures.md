# TASK-ADP-007 — Fixtures de consumidor

**Fecha:** 2026-09-23  
**Límite:** JSON de envelope en `backend/src/test/resources/storecore-fixtures/`. No se copia el OpenAPI.

`pin.json` fija path/version/`7b907a2e…de30` y rates 30/10/60/5. El digest superseded `aba69747…` falla. Envelopes PENDING/RESERVED/EXPIRED/404/409/410/reconcile/429/403 se mapean por `errorCode`/state.

`StoreCoreConsumerContractTest` OK.
