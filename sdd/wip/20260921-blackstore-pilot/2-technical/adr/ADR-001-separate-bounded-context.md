# ADR-001 — Separate bounded context

**Status:** proposed

BlackStore is an optional companion, not a StoreCore module. It has separate process/container, DB, DB credentials, service identity, secrets and audit even when colocated on the same VM. Only a versioned StoreCore API is allowed; direct DB drivers, credentials and cross-DB FKs are prohibited.
