# CLR-T09 — cierre local de residuales browser

Fecha: 2026-11-05. Base: `master c7ce4bd`; rama local
`codex/blackstore-clr-acceptance`. Este registro acredita ejecución local del
árbol de trabajo; el SHA exacto, las revisiones Astra y CI se completan después
del commit. No acredita homologación, publicación ni conectores externos.

## Alcance ejecutado

- Pérdida de respuesta de un egreso: el POST real se envía una sola vez con
  `route.fetch({maxRetries: 0, maxRedirects: 0})`; PostgreSQL confirma el commit
  y sólo la respuesta a Chromium se aborta. Reload recupera por GET el mismo
  `commandId`, IndexedDB avanza monotónicamente a `Resolved` y no existe un
  segundo POST ni duplicación de gasto, settlement, receipt, ledger o auditoría.
- Dos pestañas del mismo BrowserContext/SID: B queda hidratada antes del claim
  de A. El claim competidor se rechaza antes del HTTP; B recupera el comando A
  por GET, y la llegada tardía de A no degrada `Resolved` ni altera el `tabId`.
- `RELEASE_PENDING`: reserva, captura y reversa se realizan desde UI real. El
  JAR propio reinicia con worker apagado, admite RELEASE y pierde la respuesta.
  Reload obtiene el receipt del mismo commandId y conserva
  `ReceiptVerifiedAwaitingRefresh`; cierre UI y POST real SID+CSRF quedan
  bloqueados por `NonTerminalSale` sin hechos contables nuevos. Tras reiniciar
  sólo el worker, el mismo outbox se aplica una vez, la proyección llega a
  `RELEASED`, reload GET-only resuelve el journal y el cierre resulta posible.

La reparación detectada por el test elimina el fallback inventado `price-v1`:
`CommitInventoryCommand` y `ReleaseInventoryCommand` reciben las versiones de
precio aceptadas desde la evidencia durable de la reserva. El fixture reiniciado
usa esa precondición no vacía, conserva `price-demo-1` y la evidencia terminal
no degrada a `UNKNOWN`. Producción HTTP conserva el mismo puerto y puede ignorar
el campo esperado; no se modifica el contrato wire ni la migración.

## Evidencia roja conservada

La primera matriz completa terminó 10/11: RELEASE pagó `1` antes de que el
precio autoritativo reemplazara el placeholder y creó cobertura total. Se
endureció el preflight a precio mayor que 100. Los reruns siguientes descubrieron
dos fallas reales, no maquilladas: refresh general durante lock del runtime y
evidencia terminal `UNKNOWN` tras reinicio por pérdida de memoria del fixture.
La prueba pasó sólo después de recuperar por reload GET-only y propagar las
versiones aceptadas durables al comando terminal.

## Resultados locales

- Backend `test` final: PASS, 294/294, 71 suites, 0 fallos/errores/skips,
  6 min 1 s. Incluye el test de fixture frío para continuidad de evidencia de
  precio. El `bootJar test` previo también pasó 293/293 en 6 min 17 s.
- `npm run typecheck:dct`: PASS.
- Playwright discovery: 11 tests en 5 archivos.
- Playwright PG16/JAR/Angular: 11/11 PASS en 5,6 min. Cada caso eliminó por ID
  su contenedor y detuvo sus procesos propios; puertos 8081/4201 quedaron libres.
- `git diff --check`: PASS.
- Recursos: cero contenedores DCT y cero listeners 8081/4201; Docker Desktop
  detenido después de validar la liberación de recursos.

## Corrección de revisión exact-head

Dos revisores Astra independientes coincidieron en un P2 de aceptación sobre
`5693fc1`: la lectura final no demostraba que la pestaña tardía mantuviera
`Resolved` durante el refresh, sólo después de terminarlo. Se retuvo la respuesta
real de proyección de A después de `route.fetch`, se comprobó IndexedDB mientras
el refresh seguía en vuelo y recién entonces se liberó la misma respuesta.
`typecheck:dct` y el escenario cross-tab focal pasaron 1/1 en 28 s con teardown
total. Dos re-reviews Astra sobre `e3b342fc8147831c16a0ae648a6f2b16fca11484`
aprobaron el diff acumulado sin P0–P3: bugs/producto y
seguridad/arquitectura. El commit documental que registra las conclusiones
requiere comprobación final propia; CI actualizado continúa pendiente.

## Límite del resultado

La suite certifica Browser→BlackStore, SID/CSRF/RBAC, IndexedDB, JAR y
PostgreSQL 16 con el adaptador StoreCore fixture. No certifica transporte HTTP
StoreCore live, pérdida de respuesta después de un efecto remoto, restitución
de stock real, credenciales, canary ni homologación. Esos gates permanecen en
`release/1.0` y no se habilitan por este PASS local.
