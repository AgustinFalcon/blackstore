# PR49 — validación local del pago simulado

Fecha: 2026-10-20. Padre exacto PR48:
`f9c557b6ec21ef0216dbd4e4e3b926c5ee4ac5f1`.
Branch: `codex/split-blackstore-pr49`.
Validación del source previo al commit nominal; este archivo forma parte de ese
commit. No hereda aprobaciones ni CI de ramas históricas.

## Resultado

- `npm run test:demo:domain`: 67 specs, 0 failures.
- `npm test -- --reporters=dots`: Chrome Headless 155, 311/311 SUCCESS.
- `npm run typecheck`: app y spec PASS.
- `npm run typecheck:demo`: PASS.
- `npm run typecheck:dct`: PASS (no ejecución DCT/backend de este corte).
- `npm run build`: PASS; bootstrap demo y operativo siguen siendo chunks separados.
- `npm run test:demo`: 43/43 PASS, 38.2 s, 3 workers.

Build y suite Angular fallaron inicialmente bajo sandbox al resolver directorios
padre de esbuild: acceso denegado, antes de assertions. La ejecución autorizada
fuera del sandbox pasó. El primer browser run detectó expectativas históricas
incompatibles con PR48 (semilla por turno, selector de rol del shell, permisos de
caja del Cajero y terminal electrónico explícito); se adaptaron a esos contratos.
Un segundo run tuvo 41/42 por una inserción errónea de aprobación electrónica en
el test de efectivo; se corrigió. La pasada final completa dio 43/43 incluyendo
el nuevo recorrido por teclado y downgrade durante Pending.

## Riesgos cerrados con evidencia

payment-lifecycle.spec.ts cubre cada medio, cada resolución electrónica y retry;
Unknown/mapper/transición, doble resolución, replay y payload/cart incompatibles;
descuento pendiente tras downgrade; mixto/reportes/devolución, efectivo insuficiente
sin mutación y fallo simulado recuperable. Las regresiones PR48 mantienen permisos,
Unknown, calendario Buenos Aires, prorrateo entero, stock seguro y secuencia atómica.

Browser comprueba ausencia de HTTP de negocio, cookies y storage operativos
intactos con SID ausente/válido/expirado, recarga/cambio de superficie, perfil
ficticio y permisos revalidados. Se mantiene bloqueo del endpoint de parada HTTP.
Terminal y retry reciben foco; teclado, labels, skip-link y drawer/Escape pasan.

Capturas generadas e inspeccionadas visualmente:
frontend/demo-test-results/screenshots/390-qr-pending.png,
390-compact-pos.png, 768-compact-pos.png, 1440-compact-pos.png y recibos responsive.
Se verificaron controles legibles, sin recorte horizontal y referencias/QR
identificados como ficticios. Los archivos de resultados son outputs ignorados.

## Límites

Sólo demostración en memoria. No procesador de pagos, credenciales, stock externo,
autenticación real ni persistencia durable. No despliegue/publicación, live,
homologación fiscal ni E2E StoreCore. CI alojado y revisión independiente de este
head: NOT_RUN. Estos resultados no declaran GO global ni cierre del WIP.

## Corrección de revisión security P3 — validación posterior a 6eb10b9

Se cerraron los dos hallazgos de revisión sin push. Rejected/Cancelled comparan
fingerprint de contexto aun con carrito vacío. Approved admite únicamente el
contexto consumido registrado (carrito vacío, cliente original, descuento cero),
además de conservar idéntico método, recibido, referencia y componente efectivo.
El test reproduce para ambos estados la secuencia exacta intento→resolución→
clearCart→cliente c2→descuento 15→sameId: denegación y mismo snapshot, sin publicación.
Otro test valida replay Approved sin publicar y denegaciones por payload/cliente/
descuento distintos. El traductor y los tipos cerrados se mantienen.

La UI eliminó el alias bancario potencialmente direccionable. Muestra
DEMO—NO-PAGAR junto a «No es un alias ni una cuenta bancaria» y «No envíes dinero».
Browser verifica esos textos, ausencia del alias anterior y ausencia de enlaces.

Resultados de esta corrección (sustituyen los conteos anteriores para este source):

- Dominio: 70/70 PASS.
- Angular/Chrome Headless 155: 314/314 PASS.
- Typechecks app/spec, demo y DCT: PASS.
- Build: PASS, ejecutado fuera del sandbox por el límite esbuild ya descrito.
- Browser afectado: payment-scenarios.spec.ts 9/9 PASS, 8.5 s; incluye teclado,
  Pending/downgrade, los tres electrónicos, mixto y 390/768/1440 px.
- Browser completo anterior 43/43 es antecedente; no se reejecutó completo en
  esta corrección localizada. Revisión independiente del nuevo head y CI: NOT_RUN.
