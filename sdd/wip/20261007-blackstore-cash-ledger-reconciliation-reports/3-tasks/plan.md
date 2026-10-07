# Plan y cortes revisables

Estado active. GO de diseño aprobado 2026-10-07 tras revisiones Astra funcional/arquitectura, Sol factibilidad/Bugbot y Sol seguridad, con hallazgos resueltos. Los pasos de implementación se ejecutan sólo en los cortes secuenciales siguientes y conservan sus gates propios.

1. PR SDD/GO: revisar alcance, fórmulas, legado, terminalidad de ventas, protocolo global de locks, V8 y permisos; resolver hallazgos funcionales/arquitectura/seguridad; fijar base y versionado contractual. No sustituye aceptación DCT pendiente.
2. PR dominio + migración: tipos cerrados y traductores, políticas puras, puertos, V8 aditiva, constraints/roles/cobertura y contratos. PG16 clean/upgrade poblada e inmutabilidad. Revisar numeración V8 contra master antes de crearla.
3. PR escrituras + arqueo: adaptar todos los writers, receipts/idempotencia, pago/reversión/fee/egreso/apertura/reconocimiento/cierre y orden caja→venta→delivery; kill switch. Atomicidad, crash, carreras, legacy cierre Unavailable y authority tests PG16. Sin camino antiguo activo que salte postings.
4. PR lecturas SHIFT/DAY: repositorio snapshot consistente, filtros/timezone [inicio,fin), reconocimiento comercial y movimientos separados, fórmulas/versiones/completitud y API tipada. Boundary/DST/fan-out/RBAC; no cero sintético para datos faltantes.
5. PR UI + aceptación: dominio TS/mapper/store, lectura de recibos, estados de arqueo y completitud; browser con SID/PG16/reinicio, CI y reviews independientes exact-head. Documentar rollback/kill switch y teardown; integrar sólo el corte aprobado.

Cada PR se revisa contra su head exacto; un cambio posterior invalida la revisión heredada. CI y evidencia almacenan source/build/migración/PG major. Dependencias secuenciales: diseño→dominio/schema→writers→queries→UI/aceptación. Los contratos y tests de dominio pueden prepararse dentro de su PR; ningún runtime se activa sólo por merge de V8.

Matriz obligatoria: tipos/Unknown; money/signos/formulas; PG16 clean+V7 upgrade; grants/inmutabilidad; atomicidad/fallos; crash pre/postcommit; command replay igual/mismatch/concurrente; carreras pago-cierre, gasto-cierre, reconocimiento-cierre y worker; SHIFT/DAY/cutoff/DST; legacy incomplete; RBAC/ownership opaco; browser/reinicio; kill switch/recovery/rollback compatible. Artefactos por tarea en evidence/. PASS requiere assertions ejecutadas y teardown propio, no sólo descubrimiento de tests.

Gate final local: CLR-001..010 satisfechos; regresión backend/frontend, PG16 y browser reales verdes, reviews bugs+seguridad+SDD aprobadas y sin hallazgos abiertos, compatibilidad Flyway y recibos verificada. Homologación, publicación, companion live, fiscal y dependencias externas permanecen separados. No `/sdd.finish` por completar únicamente este corte core.
