# Pointer — StoreCore canonical OpenAPI (not a second contract)

**Status:** superseded as a hand-maintained spec. This file is Markdown on purpose: the only contractual `.yaml` lives in StoreCore.

Canonical file (copy/implement against this only):

`C:/Users/agustin/Desktop/StoreCore/sdd/wip/20260921-storecore-pos-integration-contract-v1/2-technical/api/blackstore-integration.openapi.yaml`

- version: `1.0.0-draft`
- prefix: `/blackstore-integration/v1` (no trailing slash)
- `x-canonical: true` lives in StoreCore
- full envelope: every non-304 JSON requires `code,data,errorCode,retryable,message,traceId`; status schema `code` is `const`; success uses explicit null errorCode/retryable/message and error uses data=null

Do not evolve a second OpenAPI here. Ports/DTOs/fixtures in BlackStore must track the StoreCore YAML. Historical ISSUE/REVERSAL drafts (`blackstore-pos-core`, `pos-sales-ingestion`) are superseded.
