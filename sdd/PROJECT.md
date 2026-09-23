# Project Configuration — BlackStore

> Canonical configuration, 2026-09-21. Deny-by-default.

```yaml
product:
  release_baseline: sdd-v1.0.0
  core_capability_release: blackstore-pilot (pos-core ISSUE superseded)
  deployment_model: single-tenant-per-vm-alongside-storecore
  installation_scope: one merchant, one BlackStore process, one PostgreSQL (BlackStore), talks to one StoreCore installation
  anti_goals: [shared-runtime, store_id, storecore-ticket-copy, fiscal-avoidance, dual-books, storecore-invoicing-of-pos, allow_oversell]
stack:
  backend: Kotlin/Spring Boot 3.x hexagonal
  frontend: Angular 22 clean architecture (POS console)
  database: PostgreSQL 16 with Flyway
storecore:
  canonical_openapi: ../StoreCore/sdd/wip/20260921-storecore-pos-integration-contract-v1/2-technical/api/blackstore-integration.openapi.yaml
  base_path: /blackstore-integration/v1
  sibling_wip: ../StoreCore/sdd/wip/20260921-storecore-pos-integration-contract-v1/
```

## Límites

- Una instalación = un comercio. Sin `store_id`. Sin DB cruzada.
- Identidad wire StoreCore = cuádruple de headers. `aggregate_operation_key` es derivada, no única evidencia.
- Factura presencial: titular/contador. StoreCore no factura el mostrador.
