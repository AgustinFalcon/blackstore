# Spec técnico

## Decisiones

- `AppSurface` reconoce exactamente `/caja/cierre` y `/ticket/<segmento-opaco>`; segmentos adicionales o decodificados con `/`, `?`, `#`, `.` o `..` siguen en `Unknown`.
- `CashPageMode`, `CashCloseAvailability` y `SaleSnapshotState` son clases cerradas con constructor privado, instancias estáticas, `Unknown` y único `fromWire`.
- `SaleRouteId` es value object, no estado finito. Reutiliza `SaleTextV2.ref` para validar el contrato opaco sin normalizar ni cambiar mayúsculas.
- `SaleSnapshotStore` es lectura aislada del store mutante: lista páginas por `GET`, exige una coincidencia exacta, luego consulta el detalle existente por `operationId` y correlaciona la cuádruple.
- Cada carga cancela la anterior; cambio de sesión y destroy cancelan la cadena completa y limpian datos.
- `CashSessionComponent` recibe el modo por `route.data`; el comando, la confirmación, el fingerprint, el journal y el recovery siguen siendo los existentes.
- `nextCursor` se valida como bigint decimal positivo dentro de `Long.MAX_VALUE` en el traductor de borde.

## Límites de seguridad

- Los guards sólo mejoran UX; el backend sigue autorizando existencia y ownership.
- Navegación ODR realiza únicamente GET. No hay recuperación automática por POST.
- `saleId` no concede autoridad de mutación y nunca sustituye al `operationId` del comando.
- Wire desconocido o contradictorio no se imprime como estado válido.

## Pruebas requeridas

- tipos cerrados y value object: casos válidos, `Unknown`, Unicode inválido, case sensitivity;
- superficie/rutas/guards y deep-link reload;
- resolución paginada única, ambigua, inexistente, cursor inválido/repetido, cancelación y correlación del detalle;
- componente de ticket sin botones mutantes;
- caja workspace vs cierre, incluyendo ausencia/ambigüedad de selección;
- suite Angular completa, lint, build y diff check;
- browser real adaptado al POSC-E antes de integrar.
