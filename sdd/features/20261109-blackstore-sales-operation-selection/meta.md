# BlackStore sales operation selection

- Feature: `20261109-blackstore-sales-operation-selection`
- Prefix: `BSOS`
- Status: `complete`
- Project mode: `brownfield`
- Base: `origin/master` at `f3aa6b9`
- Branch: `codex/blackstore-sales-selection`
- Scope: BlackStore productive frontend `/ventas`; existing BlackStore read API only.

## Boundaries

- No endpoint, DTO, permission, schema, migration or provider change.
- No StoreCore live adapter, fiscal, QR, payment-provider homologation or deploy claim.
- Navigation and selection are GET-only; existing journaled commands and authorization remain authoritative.
- `/ticket/:saleId`, `/ticket?operationId=…` and demo routes retain their current meanings.

## Affected specifications

- Overrides: none.
- Extends: none.
- Deprecates: none.
