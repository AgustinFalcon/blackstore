# Secuencia de PRs y límites

Todos los cortes se apilan en este orden, con base/head registrados y revisión propia. D sólo fija diseño; no es implementación del frontend completo.

1. **POSC-D — diseño:** estos documentos, fixtures especificadas, trazabilidad y decisión sobre compatibilidad. Salida: SHA documental limpio y revisión de diseño resuelta; B no inicia por inferencia de CI anterior.
2. **POSC-B — backend primero:** admisión v3 multiline, tipos/codec, validación de todas las líneas, persistence forward-only, recibos/queries correlacionados y paridad v1. Debe demostrar atomicidad/replay/recovery PG16 y seguridad HTTP antes de usar UI nueva.
3. **POSC-T — tipos y traducción frontend:** dominio puro/cart/políticas/pasos, codecs por versión, cliente v3 y journal no destructivo; fixtures/golden parity Kotlin/TS. No publicar UX que llame un backend aún ausente. Prueba de upgrade de journal v1 obligatoria.
4. **POSC-U — UI:** carrito en ruta de venta existente, reserva separada del cobro, medios/importe/split explícitos, lectura autoritativa y confirmación final. Accesibilidad/responsive/errores y dos tabs. Componente compone casos de uso; no reglas de negocio duplicadas.
5. **POSC-E — evidencia:** browser real + JAR + PG16 + fixture declarada, reinicio/pérdida de respuesta/upgrade/concurrencia, CI y reviews del head exacto. Publica comandos/assertions y límites. No cambia gates live ni homologa con datos simulados.

## Allowlist

- D: `sdd/wip/20261008-blackstore-pos-cart-explicit-payment/**`, entradas acotadas en `sdd/STATUS.md`, `sdd/BACKLOG.md`, `sdd/TRACEABILITY.md`.
- B: `backend/src/main/kotlin/com/blackstore/domain/sales/**`, puertos sales/catalog requeridos, `application/sales/**`, DTOs sales, controlador v3 y clasificación explícita de rutas en seguridad; adaptadores persistence de admisión/ticket/lectura implicados y composition root. Sólo nueva migración en `backend/src/main/resources/db/migration/`; tests correspondientes en backend. Ampliación puntual de lectura/política de catálogo para snapshot coherente con prueba; no reescritura global.
- T: `frontend/src/app/core/domain/**` sólo tipos de venta/cart/contrato; codecs/mapper/cliente de venta y adaptador journal en core/infrastructure; runtime de comandos y stores relacionados; `features/sales/ticket-steps.ts` o nuevos pasos puros; tests adyacentes y golden fixtures.
- U: `frontend/src/app/features/sales/**`, stores de carrito requeridos y estilos compartidos sólo si usados por esta pantalla. Mantener navegación/rutas existentes salvo modificación estricta de acceso a venta actual.
- E: suites/harness específicos en `frontend/e2e/**`, tests backend de contrato/PG, docs testing y evidencia del WIP. Workflow sólo si necesita job específico, conservando permisos read-only/actions pinneadas y sin autoactivación operativa.

En B/T/U/E: actualizar evidencia y tasks de este WIP. Cualquier archivo fuera de estos límites requiere justificar dependencia concreta y revisión de alcance antes de mezclarlo. No introducir otros cambios de dominio como conveniencia.

## No-touch

StoreCore/ML, contrato OpenAPI canónico y sus pins, credenciales/config live, perfil operativo/activación contable, master/release merges, tags/deploy/publicación. No editar migraciones aplicadas ni rehash de recibos v1; no borrar journal, ledger, outbox, evidencia o históricos. Sin descuento/costo editable, sincronización inventada de stock, fiscal, devoluciones posventa, nuevo sistema de caja/reportes o rediseño general. No modificar golden v1 para hacer pasar nuevas pruebas.

## Trazabilidad

POSC-001→B/T/U/E; POSC-002/003→B/T/U/E; POSC-004→B/T/E; POSC-005→B/U/E; POSC-006/007/008→B/T/E. Cada requisito se verifica con casos A01–A12 del plan de aceptación. D no cierra ninguno por documentación.
