# Progreso

- [x] Cinco artículos fixture definidos.
- [x] Regresión HTTP ampliada.
- [x] Suite backend completa (`gradlew test`: BUILD SUCCESSFUL).
- [x] Angular runtime/build actualizado a 22.2.1 para eliminar la alerta GHSA-ff3f-86qr-9cv3 de la dependencia runtime (la SPA no usa SSR, condición del advisory).
- [x] Recorrido catálogo → reserva → evidencia cubierto: el fixture certifica exactamente la versión de precio solicitada.
- [x] Auditoría de dependencias de producción (`npm audit --omit=dev`: 0 vulnerabilidades).
- [x] Regresión HTTP focalizada (`CashAndSaleControllerTest`: BUILD SUCCESSFUL).
- [x] Regresiones del fixture corregidas y revalidadas (`CashAndSaleControllerTest`, `StoreCoreRecoveryPolicyTest`, `AutonomousCoreTest`: BUILD SUCCESSFUL): reserva técnica vacía conserva fallback `price-v1`; la saga con catálogo demo espera `price-demo-1`.
- [x] Smoke dinámico por el proxy del frontend: `/`, `/caja`, `/catalogo`, `/ticket`, `/reportes`, catálogo de 5 artículos y reserva `RESERVED` con recibo.
- [ ] Build/test frontend posterior a 22.2.1: bloqueado localmente por ACL de Windows al resolver rutas por encima del workspace; requiere CI hospedado.
- [ ] Deuda de tooling: la auditoría completa conserva 6 vulnerabilidades altas transitivas del stack Karma/chokidar/braces; no se aplicó el downgrade disruptivo sugerido por `npm audit fix --force`.
- [x] Reviews Sol 6.1 funcional/SDD y seguridad: APPROVE, sin P0–P3.
- [ ] CI hospedado sobre el SHA publicado.
- [ ] Suite local completa posterior: 2 pruebas Testcontainers bloqueadas por daemon Docker inaccesible; el resto debe repetirse tras estas correcciones y en CI.
- [ ] Dos reviews Grok genuinas si el repositorio las exige para el PR final.

No autoriza integración live, ARCA, Mercado Pago, Correo Argentino, deploy ni merge.
