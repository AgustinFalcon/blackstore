# Feature: StoreCore connector adapter

**Feature ID:** `feat-20260921-storecore-connector-adapter`  
**Feature UUID:** `ba317f70-8c3c-4ba4-bb8a-dadf4d8237f8`  
**Mode:** standard · **Project type:** production · **Language:** es

Formalizes the BlackStore StoreCore HTTP adapter. It consumes only StoreCore's canonical `1.0.0-draft` YAML. Local loopback/fixture is implemented; live companion remains blocked.

**Status:** `ready_for_sol_review` (**not approved**)

- functional: `ready_for_sol_review`
- technical: `ready_for_sol_review`
- tasks: `ready_for_sol_review`
- implementation: `local_loopback_adapter` (ADP-001..010 + L3 fixture/localhost; **not approved** live)

## Related

- BlackStore `20260921-blackstore-pilot`: this WIP resolves `DEFERRED-STORECORE-CONNECTOR-001` for local loopback/fixture (ADP-001..010). Live companion remains unauthorized.
- StoreCore `20260921-storecore-pos-integration-contract-v1`: producer of the contract/evidence; its 12-task plan is unchanged.
- Canonical YAML baseline `1.0.0-draft`: `C:/Users/agustin/Desktop/StoreCore/sdd/wip/20260921-storecore-pos-integration-contract-v1/2-technical/api/blackstore-integration.openapi.yaml`.
- Canonical SHA-256 baseline: `7b907a2e11c52a66b7253407fb3f9450cae7b792beccf34c1636be9d3945de30`.
- Any version or digest change requires Sol re-review before any code may run.

## Relationship check

```yaml
relates_to:
  - path: sdd/wip/20260921-blackstore-pilot/1-functional/spec.md
    relationship_type: extends
    note: Post-GO adapter execution preserves BlackStore ownership and companion boundaries.
  - path: sdd/wip/20260921-blackstore-pilot/2-technical/spec.md
    relationship_type: extends
    note: Replaces only the fixture adapter after GO; no domain, database, or fiscal boundary changes.
  - path: ../StoreCore/sdd/wip/20260921-storecore-pos-integration-contract-v1/
    relationship_type: depends_on
    note: Consumer blocked until evidence for TASK-PIC-001 through TASK-PIC-008 and Sol GO.
```
