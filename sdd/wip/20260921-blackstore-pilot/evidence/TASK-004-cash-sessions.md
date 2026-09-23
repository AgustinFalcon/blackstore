# TASK-004 — Roles, terminals and cash sessions

**Completed:** 2026-09-22

`RoleAuthorizationPolicy` and `CashSessionBook`. CASHIER operates only their session. SUPERVISOR can override. OWNER configures. AUDITOR reads audit and cannot mutate. One open session per terminal. Closure appends an audit event and keeps the opening cash.

Gate: `AutonomousCoreTest.rolesFollowSessionOwnership` and `onlyOneOpenSessionPerTerminalAndClosureIsAudited`.
