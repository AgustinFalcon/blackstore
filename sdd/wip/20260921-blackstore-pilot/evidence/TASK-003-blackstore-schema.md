# TASK-003 — BlackStore schema

**Completed:** 2026-09-22

## Acceptance criteria

| AC | Result |
|----|--------|
| AC-1: every FK remains within BlackStore database | PASS — migration has no `dblink`, `postgres_fdw`, JDBC URL, or extension; Testcontainers query finds 0 FKs outside `public` |
| AC-2: sales/cash/inbox/outbox/audit are append-only | PASS — as `blackstore_app`, INSERT into `audit_events` succeeds and UPDATE/DELETE fail |
| GATE: Flyway/Testcontainers migration passes | PASS — PostgreSQL 16 via Testcontainers, `V1__blackstore_schema.sql` |

## Environment

Docker Engine 29 requires API 1.44. `src/test/resources/docker-java.properties` sets `api.version=1.44`.

PostgreSQL rejects the spec's comma-separated `CHECK` lists. The migration keeps the same predicates joined with `AND`, and parenthesizes `OR` groups.
