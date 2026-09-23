# TASK-005 — Catalog fixture and stale policy

**Completed:** 2026-09-22

`FixtureCatalogAdapter` is the default catalog port. `CatalogSalePolicy` blocks a new sale when the snapshot is missing, stale, or past valid-until. The snapshot remains readable. No real StoreCore client.

Gate: `AutonomousCoreTest.staleCatalogBlocksSaleAndStaysReadable`.
