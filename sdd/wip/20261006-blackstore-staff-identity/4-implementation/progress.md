# Progreso y evidencia

Base `b9211764f525d723d020c780d4eb62564ffaadf6`.

- [x] SDD funcional/técnico, matriz RBAC/ownership y amenazas aprobados por reviews funcional y seguridad sobre `804826cbaaa75b476ce9ae2803da2a156656b211`.
- [x] Backend: tipos cerrados, V4, sesiones/preauth/rate buckets JDBC, login por pasos, filtro deny-by-default, cookie/CSRF, ownership, actor confiable, provisionamiento y runbook.
- [x] Frontend: `/sesion`, estados/permisos cerrados, mapper/store, bootstrap, guards, interceptor CSRF, API relativa, logout, identidad real y aislamiento por generación.
- [x] Headers `X-Actor-Id`/`X-Role` eliminados como autoridad de producción; el interceptor también los descarta defensivamente.
- [x] Frontend local: `npm run build` y `npm run typecheck` verdes.
- [x] Suite Angular compiló desde staging limpio; ChromeHeadless del host abortó antes de assertions por subsistema crypt/GPU de Windows.
- [x] Backend con Spring Security canónico de Boot 3.3.5: main/tests compilan; CI alojado `37406898957` verde en backend y frontend sobre `f04c7d6af1bc99122187d612f4c0c3d7773091b1`.
- [x] Las suites PostgreSQL/Testcontainers pasaron en CI Linux. El host no expone Docker, por lo que la aceptación local usó PostgreSQL 18 efímero en loopback.
- [x] Browser real Angular → proxy same-origin → backend → PostgreSQL: owner login, apertura de caja ajena con motivo, logout/revocación, deep-link anónimo, login cashier, caja propia y denegación de reportes. La cookie autenticada no fue visible en `document.cookie`; la mutación CSRF persistió y quedó auditada con actor owner. Evidencia: `evidence/SID-T09-browser-real.md`.
- [x] Durante la aceptación se corrigió el entry point ambiguo de `bootRun` causado por la herramienta de provisión y se ocultó el acceso rápido de reportes a roles sin permiso.
- [ ] CI y reviews exactas sobre el commit final que incorpora esas dos correcciones y esta evidencia.

No se afirma homologación, recovery durable de ventas, reportes correctos por período, integración StoreCore live, fiscal ni `/sdd.finish`.
