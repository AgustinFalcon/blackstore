# Evidencia — lectura stale posterior a cobro POSC

Fecha: 2026-11-13. Baseline: `master`
`973e7cd985de7bee5c5ff357e88e5d15a0954dfb`.

## Hallazgo

El DCT completo reprodujo 17/18 escenarios aprobados y un fallo en el flujo
móvil de 360 px. El segundo cobro dividido ya estaba confirmado, pero la lectura
inmediata de la proyección todavía devolvía el saldo anterior. La UI trataba esa
lectura transitoriamente stale como una contradicción definitiva y entraba en
recovery. Dos repeticiones aisladas pasaron, confirmando una carrera de
visibilidad bajo carga y no un defecto de layout.

## Corrección

- `PaymentProjectionState` es un tipo cerrado con casos `Expected`, `Stale` y
  `Conflict`; ninguna capa compara etiquetas ni strings de estado.
- `AwaitPaymentCoverageStep` es un paso de flujo independiente. Sólo vuelve a
  consultar cuando el saldo observado coincide exactamente con el saldo previo.
- El saldo esperado se acepta únicamente si equivale al previo menos el cobro
  confirmado. Cualquier otro delta falla cerrado de inmediato.
- La espera está acotada a 20 lecturas cada 100 ms. No reenvía el POST ni
  rehabilita respuestas desconocidas.
- Angular runtime se actualizó de 22.2.1 a 22.2.6 y CLI/build a 22.2.7. El
  override de `zod` 4.6.7 y las correcciones transitivas no forzadas dejaron la
  auditoría completa en cero vulnerabilidades.

## Gates finales locales

- Backend Gradle: `BUILD SUCCESSFUL`.
- Frontend: 435/435 pruebas Karma, typecheck app/spec, build productivo y
  auditoría npm completa con 0 vulnerabilidades.
- Matriz de superficies: 10 superficies y 9 rutas operativas, PASS.
- Demo comercial: 52/52 PASS.
- ODR: 10/10 PASS.
- DCT real: 18/18 PASS en 8.9 minutos sobre PostgreSQL 16, JAR real y Angular;
  incluye 360/768/1280 px, reinicio, pérdida de respuesta, SID/CSRF/RBAC,
  ownership, pestañas competidoras y recovery GET-only.
- Teardown: cero contenedores Docker activos al terminar.

## Límites

La evidencia acredita el runtime local BlackStore-owned y la corrección POSC.
No acredita StoreCore live, facturación, proveedor de pagos real, transportista,
deploy, homologación ni publicación. El cambio todavía requiere revisión, CI
remoto, PR y merge; no hereda aprobaciones de commits anteriores.

## Cierre remoto reconciliado — 2026-11-14

- Head funcional: `7d44bb474d2aa913fa8f38a9261c84fe81f16817`.
- PR #69 fusionado en `master` como
  `4da1775dcc707a20bae074937d471e3d3b4d7915`.
- Verify PR `43376580181`: cuatro jobs verdes.
- Verify post-merge `43377869294`: cuatro jobs verdes.
- La evidencia no amplía los límites anteriores: proveedor real, StoreCore
  live, fiscal, transportista, deploy y homologación siguen sin acreditarse.
