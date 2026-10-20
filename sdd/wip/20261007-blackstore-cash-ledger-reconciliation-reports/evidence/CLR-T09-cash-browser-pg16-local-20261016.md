# CLR-T09 — incremento local PostgreSQL 16 y browser de caja/ventas

> Antecedente externo: este overlay está conservado en el snapshot
> `115fad53006b55d27b021883bf49153a3de050fa`; no acredita el head separado #45.

Fecha: 2026-10-16. Rama: `feat/cash-ledger-frontend-t08d`. Base commit:
`9f4d29ef98c2fb6101f385e83dcf985d0827529f`. Los cambios productivos quedan
acotados al valor inicial de descuento compatible con el contrato V2 y a la
migración forward-only V12 que impide escritores legacy tras la activación; el
resto del diff funcional pertenece al arnés DCT. No hubo push, PR, merge,
activación real ni publicación.

## Actualización del arnés

El arnés histórico DCT/V7 se alineó con el source V12 vigente: provisiona un
contexto POS explícito e inmutable para `T-1`, configura el mismo
`clientInstanceId` en el proceso, activa el runtime V2 usando
`AccountingLifecycleState`, espera los endpoints `/api/v2`, traduce los eventos
de auditoría V1/V2 mediante un tipo cerrado y conserva `Unknown` en el borde.
No introduce IDs productivos, credenciales reutilizables ni fallback live.

La matriz de denegaciones separa el envelope contable V2 del legacy, usa
`CommandFailure`/`CommandOutcome`/`ExpenseOperation` cerrados y comprueba:
autenticación, CSRF, opacidad 404, permisos, override, método desconocido,
caja cerrada, prevención al volver a foreground y conciliación explícita
GET-only sin reenvío POST.

El recorrido de ventas reveló que la pantalla iniciaba cada ticket con un
descuento local de `2`, aunque el contrato V2 vigente conserva al catálogo
como única autoridad y rechaza descuentos afirmados por el browser. Se corrigió
el valor inicial a `0`; no se relajó la validación del backend. Un descuento
futuro deberá ingresar mediante un comando de dominio autorizado propio.

## Evidencia ejecutada

- Backend `bootJar` con Java 17: PASS.
- Backend completo: 69 suites, 291 tests, 0 failures, 0 errors, 0 skipped;
  `BUILD SUCCESSFUL`. Testcontainers usó PostgreSQL 16 y finalizó sin
  contenedores activos.
- Frontend `npm run typecheck` y `npm run typecheck:dct`: PASS.
- Frontend Karma: 234/234 PASS.
- Frontend build productivo: PASS; 492.97 kB iniciales, 125.95 kB estimados.
- Playwright `npm run test:dct`: corrida final 17/17 PASS en 4.8 min. La
  corrección posterior puramente tipada del fixture mixed DAY se revalidó con
  typecheck DCT y ese escenario 1/1 PASS usando la configuración DCT completa.
  Las corridas históricas 10/10 y 11/11 son antecedentes del crecimiento de la
  matriz, no el conteo vigente. Cada escenario adquirió
  PostgreSQL 16, JAR y Angular propios y registró teardown PASS.
- Browser legacy aislado: fixture/runtime PRE_ACTIVATION propio, contrato v1 de
  apertura, egreso y cierre 1/1 PASS, sin receipts V2 y con teardown total.
- Reinicio: apertura 100.00, egreso 7.25, cierre 92.75, PID JAR nuevo, misma
  sesión staff y mismos hechos, cero replay POST.
- Denegaciones: hechos sin mutación parcial, una intención V2 cerrada rechazada,
  UI preventivamente bloqueada durante foreground y conciliación manual por GET
  sin segundo POST. Un POST v1 bien formado, autenticado, con CSRF, titular y
  terminal válidos recibe `LegacyContractDisabled` en ACTIVE y en PAUSED, sin
  quedar tapado por otra denegación previa.
- Ventas: reserva admitida `202`, reserva remota por worker fixture, captura,
  consulta autoritativa y commit hasta `COMMITTED/PAID`; recorrido alternativo
  captura, reversión, consulta `PAYMENT_CAPTURED/UNPAID` y release hasta
  `RELEASED/UNPAID`. StoreCore live permaneció deshabilitado y con kill switches.
- Pérdida de respuesta: el servidor confirma egreso, reserva, captura, reversa,
  release o commit pero el browser recibe fallo de red. Tras reload, el journal
  conserva cada `commandId`, recupera recibo y proyección únicamente por GET,
  alcanza `Resolved`, mantiene un solo POST y permite reabrir las ventas
  `RESERVED`, `PAYMENT_CAPTURED`, `RELEASED` o `COMMITTED` por su `operationId`.
  Los hechos PostgreSQL permanecen únicos y coherentes.
- Pestañas: una segunda pestaña del mismo contexto/SID, con otro `tabId`, adopta
  sólo la recuperación GET del egreso pendiente, resuelve el journal IndexedDB
  compartido y conserva exactamente un POST y un hecho contable.
- Cambio de actor: un SID diferente encuentra la evidencia pendiente pero queda
  bloqueado, no consulta el recibo ajeno, no adopta el comando y no emite POST.
  Al volver a autenticarse el actor original, recupera recibo y proyección por
  GET, resuelve el journal y conserva un único egreso persistido.
- Rotación SID: una respuesta tardía de listado iniciada por la identidad
  anterior se descarta después de logout/login y no contamina la nueva sesión.
  La prueba conserva el mismo documento mediante navegación SPA, espera el
  `route.fulfill` retenido y compara una identidad de `window` antes/después.
- Reportes y arqueo: browser real acredita captura parcial, split CASH/CARD,
  reversión de cada componente, accrue/settle, Balanced/Shortage/Overage,
  SHIFT completo, DAY parcial por BeforeActivation, outcome desconocido neutral
  y lectura preservada bajo PAUSED. El DAY añade otro cajero con egreso de
  contraste y una terminal vacía: filtrar por titular excluye 70.00 y conserva
  10.00; cambiar a la otra terminal excluye también 10.00.
- Historial mixto: una caja legacy y una venta COMMITTED anterior sin recognition
  permanecen incompletas junto a un turno V2 completo; no se fabrican totales.
- Liberación pendiente: con worker detenido, `RELEASE_PENDING` es durable,
  visible y bloquea el cierre por venta no terminal. El browser pierde la
  respuesta del POST después del commit, conserva el mismo commandId en journal,
  recarga por GET receipt+proyección sin rePOST y queda correctamente en
  `ReceiptVerifiedAwaitingRefresh` mientras no exista evidencia `RELEASED`.
- Frontera fee: `feeAmount` continúa rechazado en captura V2 y `FEE_RECORD` sólo
  se admite mediante el puerto interno OWNER, cubierto por PostgreSQL. El
  frontend puede leer/exhibir cargos históricos, pero no existe writer browser
  que amplíe esa autoridad.
- Rollback/kill switch: V12 agrega un constraint trigger diferido sobre el
  outbox comercial, califica objetos `public`, fija un search path cerrado y usa
  el lock compartido del lifecycle. Rechaza además transacciones legacy con
  snapshot distinto de READ COMMITTED. En PostgreSQL 16,
  la emulación de writer antiguo confirmó legacy en PreActivation y rollback
  total de intención+outbox en Active y Paused. Una carrera con dos conexiones
  confirmó que, si la activación obtiene primero el fence exclusivo, el commit
  del writer antiguo espera y luego se rechaza sin intención ni outbox. Ataques
  con tabla temporal homónima y con snapshot REPEATABLE READ abierto antes de
  activar también revierten sin hechos. La
  prueba focalizada V2 confirmó que reserva, recibo, replay y consulta durante
  Paused siguen funcionando. El DCT browser completo pasó 17/17 sobre
  el schema V12.
- Restore compatible: `pg_dump -Fc --no-owner` de V12 y `pg_restore --no-owner`
  en una segunda DB PG16 preservaron migración máxima, lifecycle y hechos. El
  bootJar construido desde el commit compatible exacto `9f4d29e` arrancó contra
  la restauración, respondió por HTTP y dejó intacto `12|PAUSED|1`. Proceso,
  contenedor y worktree temporal fueron detenidos/eliminados.
- `git diff --check`: PASS.

Una corrida focalizada intermedia cerró 5/6: el nuevo control DAY intentó crear
un segundo contexto con la misma identidad y PostgreSQL rechazó correctamente la
unicidad. Se corrigió el fixture para contrastar cajeros en la terminal
provisionada y una terminal vacía sólo de lectura. El escenario focalizado pasó
1/1 y la corrida final completa posterior pasó 17/17; no se atribuye PASS al
intento fallido.

## Gate y límites

Este incremento cierra localmente PG16 clean/upgrade del backend y los recorridos
browser de caja/reinicio/denegaciones/ventas/reportes, recuperación GET-only de todos los
comandos ejercitados, journal cross-tab con mismo SID y aislamiento/recuperación
ante cambio de actor, fence/kill switch de DB y restore compatible defendidos
por PostgreSQL. Bug/Product, Security y SDD/SOLID aprobaron el diff local final
sin P0–P3. No cierra CLR-T09 completo: falta CI hospedado. Como no existe un
commit nuevo autorizado, no se denomina a esta evidencia "exact-head". Fee queda cerrado como
frontera interna sin writer browser,
no como una mutación pendiente de UI.
StoreCore live, fiscal, Mercado Pago, homologación, deploy y publicación
permanecen fuera del alcance.
