# Plan y trazabilidad

Base `a92ec07868638e312e568f3dc36837ea78cdde44`. Este documento ordena trabajo futuro; ninguna tarea de código tiene GO implícito.

1. Revisión Sol funcional/arquitectura y seguridad del SDD; resolver hallazgos y registrar GO del alcance local.
2. TP-001: tipos cerrados de cobertura, snapshot, decisión/motivo y traducción de borde. Validar T01.
3. TP-006: puertos separados por cuádruple/venta inequívoca, cardinalidad fail-closed, ledger/reversas legibles en memoria/JDBC y coordinador local compartido lectura→decisión→efecto. Validar T07/T10 y carreras T11 con barreras.
4. TP-002: gate capture, total/saldo y moneda NUMERIC(14,2) sin redondeo; DTO PaymentResponse y snapshot explícito correlacionado. Validar T02/T03/T04/T11.
5. TP-004 y TP-005: commit exacto, release sin historial y validación identidad del pago original para reverse; decisiones NewCommand/RecoverExistingCommand/TerminalReplay, sin duplicar outbox. Validar T06/T08/T09/T10/T11.
6. TP-003: pasos UI, split con amount/fee exactos, refresh correlacionado y guard previo a crear UUID. Validar T02/T05/T11.
7. TP-006: regresiones locales y CI del HEAD final, T12, revisión final Sol y evidencia honesta. Actualizar documentación canónica únicamente con resultados obtenidos.

La matriz T01..T12 ampliada es el criterio de cobertura; un requisito no se marca terminado sólo por crear su clase. Coordinación local abarca lectura, decisión y efecto; no es prueba de atomicidad durable o multiinstancia. Nuevos cambios de recovery durable, atomicidad transaccional, schemas o reportes requieren otro WIP. Los dos reviews históricos CHANGES_REQUESTED permanecen como tales; el draft corregido requiere nueva revisión y no tiene GO.
