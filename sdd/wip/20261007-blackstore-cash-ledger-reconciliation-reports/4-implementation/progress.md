# Progreso inicial

2026-10-07: kit SDD creado para TODO-007 a partir del plan Astra y fuentes locales.

2026-10-07: primera revisión Astra/Sol/Security emitió NO-GO por terminalidad tras reversión, legado, devengo inmutable, cobertura DAY y permisos/contratos. Se resolvieron con reversión íntegra por pago y saldo neto, RELEASE_PENDING→RELEASED durable, `expense_settlements` append-only, universo de cobertura independiente de postings, zona efectiva versionada y contratos/permissions de command receipts. Una segunda revisión detectó y corrigió circularidad RELEASE/restitución y precisó que “parcial” es respecto de una venta split, no del importe de un pago.

Estado active; GO de diseño aprobado por Astra funcional/arquitectura, Sol factibilidad/Bugbot y Sol seguridad. CLR-T01 done; CLR-T02..T09 planned, 1/9 done.

Se especificaron fórmulas de efectivo/flujo comercial, cierre sin ajuste automático, eventos y resultados cerrados, V8 aditiva, command receipts, reconocimiento COMMITTED separado, locks caja→venta→delivery, snapshots SHIFT/DAY y LegacyIncomplete. Son decisiones de diseño aceptadas, no afirmaciones de implementación.

Código y migraciones: NOT_RUN. Los escenarios CLR de PG16 clean/upgrade, atomicidad/crash/concurrencia/seguridad, browser/reinicio y rollback/kill switch: NOT_RUN. Reviews y GO de diseño: PASS. La infraestructura DCT base quedó integrada en PR #28 (`bca02bf`, merge `b8ce3b1`) con Bugbot y Security APPROVED, CI PR `37657282222` 3/3 SUCCESS y CI posmerge `37658142335` 3/3 SUCCESS. No deploy ni homologación productiva como parte de CLR-T01.

Próximo paso: integrar el PR documental exact-head y luego abrir el corte CLR-T02/T03 de dominio + V8. No activar StoreCore, fiscal ni publicación.
