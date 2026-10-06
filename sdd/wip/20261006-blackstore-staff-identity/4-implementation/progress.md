# Progreso y evidencia

Base `b9211764f525d723d020c780d4eb62564ffaadf6`.

- [x] SDD funcional/técnico, matriz RBAC/ownership y amenazas aprobados por reviews funcional y seguridad sobre `804826cbaaa75b476ce9ae2803da2a156656b211`.
- [x] Backend: tipos cerrados, V4, sesiones/preauth/rate buckets JDBC, login por pasos, filtro deny-by-default, cookie/CSRF, ownership, actor confiable, provisionamiento y runbook.
- [x] Frontend: `/sesion`, estados/permisos cerrados, mapper/store, bootstrap, guards, interceptor CSRF, API relativa, logout, identidad real y aislamiento por generación.
- [x] Headers `X-Actor-Id`/`X-Role` eliminados como autoridad de producción; el interceptor también los descarta defensivamente.
- [x] Frontend local: `npm run build` y `npm run typecheck` verdes.
- [x] Suite Angular compiló desde staging limpio; ChromeHeadless del host abortó antes de assertions por subsistema crypt/GPU de Windows.
- [x] Backend con Spring Security canónico de Boot 3.3.5: main/tests compilan; 115 tests ejecutados, 110 verdes.
- [ ] Cinco suites PostgreSQL/Testcontainers no inicializaron porque Docker no está disponible en el host. CI Linux es el gate autoritativo.
- [ ] Browser real contra backend+PostgreSQL, CI final y reviews exactas de código.

No se afirma homologación, recovery durable de ventas, reportes correctos por período, integración StoreCore live, fiscal ni `/sdd.finish`.
