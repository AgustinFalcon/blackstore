# Sol review — issue #20 hosted Verify CI

**Date:** 2026-10-01

**Base:** `origin/master` at `8fedbeb`

## Verdict

**APPROVED** by three independent GPT-6.1-sol review lanes at medium effort after correction.

The workflow runs real backend tests and frontend tests/build on pull requests and `master`, with read-only repository permissions, bounded job timeouts, cancellation of superseded runs, and two Gradle workers. It does not deploy, consume secrets, enable StoreCore integration, or modify product behavior.

## Corrections required and resolved

1. `backend/gradlew` is tracked without the executable bit. The job now invokes it through `bash` so Ubuntu can execute the wrapper without changing repository file modes.
2. The deny-by-default root `.gitignore` originally excluded `.github/workflows/verify.yml`. A narrow allowlist now exposes only that workflow path.
3. Every third-party action is pinned to the full commit SHA of a maintained official release; the release remains visible in an inline comment for updates and auditability.
4. Both checkout steps set `persist-credentials: false`, so Gradle, npm lifecycle scripts and tests do not retain the repository token in Git configuration.

## Local evidence

- `backend/gradlew.bat test --no-daemon`: PASS; all test tasks executed and the one-use daemon stopped.
- `npm run build`: PASS.
- `git diff --check`: PASS.
- `npm test -- --no-progress`: local Windows Angular resolution failed before Karma/assertions with `Access denied / Cannot read directory ../../../../../../..`; not counted as a pass or product failure.

Hosted success remains mandatory. The first GitHub run must show both backend and frontend jobs executing their test/build steps successfully. This review does not replace repository merge approvals.
