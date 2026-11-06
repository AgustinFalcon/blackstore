# Spec técnico

## Fronteras

- `main.ts` elige superficie antes de bootstrap. `/demo` conserva `LocalDemoRepository` route-scoped y no registra `HttpClient`, sesión, journal ni recovery.
- El operativo conserva SID/CSRF/RBAC, traductores wire, command journal durable-before-POST y recovery GET-only.
- Estados/roles/medios/pasos permanecen tipos cerrados con `Unknown`; búsqueda y texto libre no se modelan como estado.
- Los pasos de ticket siguen objetos de flujo; este corte no reescribe su dominio.

## Presentación

- `Route.title` provee títulos de documento.
- Los shells escuchan `NavigationEnd`, comparan sólo pathname y enfocan el contenedor tras navegación real; cambios query/fragment no desplazan la interacción.
- Búsqueda opera sobre snapshots ya autorizados y no afirma stock. Seleccionar conserva SKU, variante, precio y versión originales.
- Formato ARS/fecha es de presentación; valores ausentes son “No informado”, nunca cero.
- Reportes no fijan silenciosamente una zona: ofrece guía y opciones, pero el operador envía la zona autorizada.

## Gates

`typecheck`, `typecheck:demo`, `typecheck:dct`, `build`, `test`, `test:demo:domain`, `test:demo`, `test:odr`, revisión UX/producto Astra y dos revisiones Sol sobre el SHA exacto. CI debe ejecutar `typecheck:demo`, `test:demo` y `test:odr`.
