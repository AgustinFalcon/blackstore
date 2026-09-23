# Review — architecture and boundary

**Task:** TASK-013 · **Date:** 2026-09-22

No P0. BlackStore does not open a StoreCore database, driver, credential, or cross-database foreign key. `ArchitectureBoundaryTest` keeps domain free of Spring and JPA, and application free of infrastructure. StoreCore inventory and catalog are ports. The default adapter is a fixture. `BlockedStoreCoreInventoryAdapter` remains only for `mode=blocked`.

Runtime-role smoke from TASK-003: as `blackstore_app`, insert into `audit_events` succeeds and update/delete fail.

The real HTTP adapter is still out of scope.
