# Evidencia local POSC-T — 2026-11-05

## Fuente y alcance

- Base integrada: `51f109f2c824bcfcf6f3016c90c41f4a8d123b09` (BlackStore `master`, PR #52).
- Implementación revisable: `bb06433` (`codex/blackstore-posc-t`).
- Corte: tipos cerrados de contrato/carrito, política Unicode AdmissionV2, codecs V1/V2, rutas HTTP cerradas, journal V2 con upgrade V1, traducción de recibos/rechazos y reconstrucción durable completa.
- No acredita UI POSC-U, PostgreSQL/browser POSC-E, StoreCore live, homologación ni publicación.

## Gates locales

Desde `frontend`:

- `npm run typecheck`: PASS.
- `npm test -- --no-progress`: PASS, 354/354 casos descubiertos y ejecutados en Chrome Headless 156.
- `npm run build`: PASS; bundle de producción generado.
- `git diff --check`: PASS antes del commit.

La primera invocación de Karma dentro del sandbox no compiló porque el bundler no pudo leer rutas del worktree. Se repitió fuera del sandbox con las dependencias locales restauradas por `npm ci`; allí compiló y ejecutó los 354 casos. No se contabiliza la invocación abortada como prueba.

## Assertions agregadas

- Golden Kotlin/TS AdmissionV2 `fbfb0ce9…` y goldens AdmissionV1 Reserve/Commit/Release sin cambios.
- Orden UTF-8 unsigned sin mutar el cuerpo; Unicode escalar/NUL/trim exacto y límites UTF-16.
- Nueve combinaciones de upgrade V1 (tres comandos por tres fases), sin persistir ruta nueva en el envelope histórico.
- Journal V2 de 100 líneas con nombres/refs máximos, bytes UTF-8, allowlists anidadas y deep freeze.
- Reserva V3 usa POST/GET v3; commit/release V1 permanecen v2; rutas desconocidas fallan cerradas.
- `saleId` opaco conserva correlación, líneas preservan `variantId`, `originalUnitPrice` y `priceVersion`, y la suma se coteja con el total autoritativo.
- Versiones/códigos futuros traducen a `Unknown`; recibo V3 exige `sale-admission-v2` explícito.
- Commit/release V2 rehidratan referencias opacas originadas en V3, incluida la política Unicode que conserva NBSP.
- Metadata de línea V2 completamente ausente se clasifica como histórico legible; metadata parcial se clasifica `Unknown` y bloquea mutación.
- Recibos V1 sólo infieren `sale-admission-v1` cuando el discriminador está ausente; una versión explícita incompatible falla cerrada.

## Revisión

Bug/producto/arquitectura y seguridad Astra están pendientes sobre el SHA exacto. Esta evidencia no declara APPROVE ni cierre POSC-T hasta resolver hallazgos y repetir gates.
