# Feature: StoreCore connector adapter

**Feature ID:** `feat-20260921-storecore-connector-adapter`  
**Feature UUID:** `ba317f70-8c3c-4ba4-bb8a-dadf4d8237f8`  
**Mode:** standard · **Project type:** production · **Language:** es

Formalizes the BlackStore StoreCore HTTP adapter. It consumes only StoreCore's canonical `1.0.0-draft` YAML and remains blocked until StoreCore readiness evidence and an explicit Sol GO for this WIP.

**Status:** `ready_for_sol_review` (**not approved**)

- functional: `ready_for_sol_review`
- technical: `ready_for_sol_review`
- tasks: `ready_for_sol_review`
- implementation: `blocked_by_sol_gate_and_storecore_readiness`

## Related

- BlackStore `20260921-blackstore-pilot`: this WIP resolves its documentation dependency `DEFERRED-STORECORE-CONNECTOR-001`; it does not authorize code yet.
- StoreCore `20260921-storecore-pos-integration-contract-v1`: producer of the contract/evidence; its 12-task plan is unchanged.
- Canonical YAML baseline `1.0.0-draft`: `C:/Users/agustin/Desktop/StoreCore/sdd/wip/20260921-storecore-pos-integration-contract-v1/2-technical/api/blackstore-integration.openapi.yaml`.
- Canonical SHA-256 baseline: `aba6974723b47d2f5e28a170d3f6e41ecb3387c04e10debcdea097b2bff99bda`.
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
