# TASK-ADP-002 — Compatibility lock (narrow)

**Fecha:** 2026-09-23

Pinned constants + config fail-closed. The StoreCore YAML is **not** parsed here. Headers, scopes, and `info.x-canonical` stay unpinned until a later transport/fixture task.

| Campo | Valor |
|---|---|
| Path | `/blackstore-integration/v1` |
| Version | `1.0.0-draft` |
| SHA-256 | `7b907a2e11c52a66b7253407fb3f9450cae7b792beccf34c1636be9d3945de30` |

`StoreCoreContractCompatibilityGuard` calls `StoreCoreCanonicalContract.assertCompatible` on `blackstore.storecore.contract.*`. Drift throws `STORECORE_CONTRACT_INCOMPATIBLE`. GATE: `StoreCoreCanonicalContractTest` (pin + guard). No YAML parse. No HTTP client.
