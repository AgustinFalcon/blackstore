# TASK-ADP-003 — Application ports

**Fecha:** 2026-09-23  
**Límite:** puertos y valores neutrales. Sin transporte HTTP, secretos, companion live ni DSN StoreCore.

## Contrato de seis operaciones

`StoreCoreContractOperations` + `StoreCoreContractFacade` delegan a:

| Operación | Puerto de dominio |
|---|---|
| catalog | `StoreCoreCatalogPort` |
| reserve / commit / release / GET | `StoreCoreInventoryPort` |
| reconcile | `StoreCoreReconcilePort` (nuevo) |

`ReconcileProjection.unknownAuthorizesRepost()` es siempre `false`.

## Evidencia remota

`StoreCoreRemoteEvidence` y `StoreCoreRecoveryPolicy` ramifican por `state` / `errorCode`. PENDING no inventa receipt. EXPIRED exige tupla completa + `reconciliation_reason`. `OPERATION_RETIRED` es `NEVER_REPOST`.

## Validación

`StoreCoreRecoveryPolicyTest` y `ArchitectureBoundaryTest.storeCoreContractBoundaryHasNoHttpOrFrameworkTypes`. Fixture/blocked implementan reconcile local; no hay cliente HTTP.
