# CLR-T09 — correcciones finales de producto frontend

> Antecedente externo: este overlay está conservado en el snapshot
> `115fad53006b55d27b021883bf49153a3de050fa`; no acredita el head separado #45.

Fecha: 2026-10-16. Base Git exacta: `9f4d29e`. Source validado: overlay local
sin commit de la rama `feat/cash-ledger-frontend-t08d`; no se atribuye SHA nuevo.

## Alcance corregido

- El precio de una reserva es de sólo lectura y debe coincidir exactamente con
  el `unitPrice` del catálogo. El descuento V2 queda fijo en cero. La política
  fail-closed valida ambos valores antes de crear UUID, journal o POST.
- El primer medio de pago es un `PaymentMethod` cerrado seleccionable. Se admite
  tarjeta o transferencia por el total y pago dividido opcional; `Unknown`,
  importes vacíos, negativos o por encima del precio se rechazan antes del
  journal.
- Cierre de caja y reversas de pago requieren un paso `ConfirmationPhase`
  cerrado. Caja exige declarado y motivo escritos de forma explícita, congela
  la identidad/importes revisados y vuelve a validar la caja al confirmar. Los
  diálogos son modales nativos: foco aislado, Escape cancelable, backdrop y
  restauración de foco administrados por el navegador.
- Apertura, egreso y cierre validan escala y rango `NUMERIC(14,2)` mediante
  `TicketMoney` antes de UUID, journal o POST; subcentavos y overflow fallan
  cerrados sin dejar una intención imposible de recuperar.
- El shell usa una columna única debajo de 720 px, contenido `min-width: 0`,
  formularios apilados y tablas dentro de tarjetas con scroll contenido. El
  escenario completo de venta y el catálogo se ejecutaron en Chromium a 390x844
  y verificaron que no exista overflow horizontal global.

## Evidencia ejecutada

- Backend: `gradlew test` PASS, 69 suites / 291 tests, 0 fallos.
- Frontend focalizado final (caja, ventas durables y diálogo): 10/10 PASS.
- Frontend completo: 241/241 PASS.
- `npm run typecheck`: PASS.
- `npm run typecheck:dct`: PASS.
- `npm run build`: PASS; initial 501.83 kB, transferencia estimada 128.02 kB.
- Playwright DCT: 17/17 PASS con PostgreSQL 16, JAR y Angular descartables.
  Incluye caja, pagos divididos, tarjeta como primer medio, reversas confirmadas,
  cierre confirmado, PAUSED, legacy, reinicio y recuperación GET-only.
- Los 17 contenedores PostgreSQL y todos los procesos JAR/Angular propios
  informaron teardown PASS.

La primera matriz posterior al cambio de nombre accesible expuso tres selectores
E2E que aún buscaban el texto anterior. Se interrumpió tras el primer fallo, se
eliminó el contenedor efímero exacto, se corrigieron los selectores, se ejecutó
el escenario afectado 1/1 PASS y luego la matriz completa 17/17 PASS. No se
presenta la corrida intermedia como evidencia de aceptación.

## Límites

No hubo activación live, homologación, deploy, publicación, commit, PR ni merge.
StoreCore continúa en modo fixture/kill switch para este corte. CI hospedado
permanece separado. Producto/UX, técnica/SOLID y seguridad Astra revisaron este
overlay final y emitieron APPROVE sin P1/P2; ningún APPROVE anterior se heredó.
