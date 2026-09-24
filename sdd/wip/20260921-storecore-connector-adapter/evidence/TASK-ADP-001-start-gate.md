# TASK-ADP-001 — Start gate

**Fecha:** 2026-09-23  
**Sol:** `../StoreCore/sdd/reviews/20260923-sol-remaining-gates.md` = CONDITIONAL_GO fixture/localhost only.

## StoreCore handoff PIC-001..008

Accepted locally with `BlackStoreHttpContractTest` (CAS temporary ACTIVE) plus fail-closed 403 baseline. This start gate names PIC-001..008 only. PIC-009/L3 are StoreCore residuals, not a BlackStore close. Not live companion.

## Canonical pin

| Campo | Valor |
|---|---|
| Path | `C:/Users/agustin/Desktop/StoreCore/sdd/wip/20260921-storecore-pos-integration-contract-v1/2-technical/api/blackstore-integration.openapi.yaml` |
| Served copy | `StoreCore/backend/src/main/resources/openapi/blackstore-integration.openapi.yaml` |
| Version | `1.0.0-draft` |
| SHA-256 | `7b907a2e11c52a66b7253407fb3f9450cae7b792beccf34c1636be9d3945de30` |

Previous documented digest `aba6974723b47d2f5e28a170d3f6e41ecb3387c04e10debcdea097b2bff99bda` is superseded by this re-pin. Any later digest change needs Sol re-review before transport.

## Limits

No live host, secret, TLS production cert, or HTTP client until ADP-002 lock is coded against this digest. Capability stays fail-closed.
