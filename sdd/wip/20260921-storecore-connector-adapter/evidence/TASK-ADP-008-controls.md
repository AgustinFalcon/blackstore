# TASK-ADP-008 — Control plane fail-closed

**Fecha:** 2026-09-23  
**Límite:** refs opacos. Sin secreto, PAN ni fiscal.

`StoreCoreControlPlane` rota sólo identity/token refs, guarda el par anterior y lo restaura en rollback, audita actor/reason, disable/kill-switch cortan dispatch, canary>0 o `rolloutEnabled` lanza `STORECORE_LIVE_ROLLOUT_FORBIDDEN`. `application.yml` queda capability false, kill-switch true, canary 0, rollout false.

`StoreCoreControlPlaneTest` OK.
