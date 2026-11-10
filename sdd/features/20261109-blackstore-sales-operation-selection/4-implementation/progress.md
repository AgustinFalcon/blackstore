# Progress

## 2026-11-09

- Independent UX/product, Angular architecture and SDD audits confirmed the fresh-load selection gap on `origin/master` `f3aa6b9`.
- Agreed frontend-only design: URL-authoritative selection, GET-only navigation, closed phases, retry with retained identity, A→B supersession, draft isolation and accessible focus lifecycle.
- No backend, schema, permission or provider change is required.
- Implemented closed `OperationId`, route selection and lifecycle types; URL-driven selector; explicit loading/ready/unavailable/invalid states; focus lifecycle; draft isolation; capture validation and honest list failure.
- First strict review round returned REQUEST_CHANGES: a legacy `open(A)` could publish beneath selected B, Ready refresh was a no-op, loading could not close, completion was not announced and panel targets were below 44 px.
- Reworked all detail readers around a shared publication generation with a separate command generation, added selected/detail correlation and route-exit invalidation, then added refresh, loading close, live completion status and 44 px controls.
- Regression evidence now covers legacy-open→selection contamination, route exit, Ready refresh, delayed close, browser back/forward and 320/390/1440 responsive targets.
- Second strict review round found one remaining stale `consultReceipt` publisher; both accounting and sales journal callbacks now preserve any newer selection and refresh the same selection through GET.
- Third coordinated GPT-6.1 Sol review round: UX/Product APPROVE, Code/Architecture APPROVE and Security APPROVE, with no P0–P3 findings.
- Final local evidence on the frozen code head: 428/428 Angular tests, 52/52 commercial browser scenarios, 10/10 ODR scenarios, 18/18 real DCT scenarios (PostgreSQL 16 + real JAR + Angular), backend suite PASS, build/typechecks/surface manifest PASS.
