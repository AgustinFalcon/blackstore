# TASK-002 — Companion entitlement and identity

**Completed:** 2026-09-22

## Acceptance criteria

| AC | Result |
|----|--------|
| AC-1: disabled/mismatched entitlement fails closed | PASS — `CompanionEntitlementPolicyTest` covers DISABLED, SUSPENDED, installation mismatch, service-identity mismatch, and companion count other than 1 |
| AC-2: service identity is distinct from StoreCore credentials | PASS — `ServiceIdentity` has only `clientRef`; no password, secret, token, or credential field |
| GATE: entitlement boundary tests pass | PASS — startup guard runs the same policy; default config is ENABLED and matches, so the Spring context starts |

## Notes

The process binding is configuration. The persisted singleton lands in `companion_installation` (TASK-003). The real StoreCore HTTP adapter stays blocked.
