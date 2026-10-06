# Plan de implementación

1. Aprobar contrato, estados, invariantes, fronteras transaccionales y amenazas.
2. Agregar V6, tipos cerrados y mapper canónico; cuarentenar histórico incompleto.
3. Implementar repositorio durable de lectura y sustituir mapas como autoridad.
4. Implementar lock/CAS por venta y admisión transaccional de RESERVE, pago y comandos terminales.
5. Aplicar evidencia/proyección/intento/auditoría atómicamente.
6. Implementar flujo de worker por pasos, lease, backoff y recovery GET.
7. Exponer lista/detalle durable y allowed actions con RBAC/ownership.
8. Implementar store y UI de reapertura separados del flujo de nueva venta.
9. Ejecutar PostgreSQL, concurrencia/fencing, reinicio real, browser, CI y reviews exactas.

## Gate previo

No se implementa hasta obtener aprobación funcional/arquitectura y seguridad del SDD. No se habilita integración live ni `/sdd.finish`.

## Matriz mínima

- Estado × comando × evidencia completa/incompleta/Unknown.
- Propio/ajeno × rol × lista/detalle/mutación.
- Replay idéntico/diferente antes y después de reinicio.
- Crash antes/después de cada commit y de HTTP.
- Claim doble, lease vigente/vencido y backoff agotado.
- Claim A vencido, claim B aplicado y respuesta tardía A ignorada por fencing.
- Pago vs COMMIT/RELEASE con conexiones independientes bajo el mismo lock PostgreSQL.
- RESERVE/COMMIT/RELEASE aceptado remoto con respuesta perdida.
- Pago persistido y cero doble captura al reabrir.
- Histórico V5 preservado, visible y bloqueado.
- Browser nuevo y proceso backend nuevo contra la misma PostgreSQL.
- Reabrir parcial/completo/terminal/legacy/Unknown genera cero POST automático.

## Gate de cierre

CI backend/frontend/PostgreSQL, browser real con reinicio, inventario de rutas, evidencia de no tráfico live y reviews Bug/Security/SDD sin P0–P3. Reportes, fiscal y homologaciones continúan como pendientes explícitos.
