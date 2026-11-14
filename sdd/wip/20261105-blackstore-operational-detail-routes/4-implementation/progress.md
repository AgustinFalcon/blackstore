# Progreso

- [x] Auditoría paralela de master, fuente histórica y SDD.
- [x] Base limpia congelada en `822c431`.
- [x] Snapshot histórico identificado sin cherry-pick.
- [x] Specs funcional y técnico aprobados por alcance previo del usuario.
- [x] Implementación y pruebas focales.
- [x] Suite, build, contratos TypeScript y browser real.
- [ ] Revisiones independientes sobre head exacto.
- [ ] PR/CI/merge.

No reutilizar como evidencia del port actual los PASS históricos del snapshot `115fad5`.

## Evidencia local del port actual

- `npm run typecheck`: PASS para aplicación y specs.
- `npm run typecheck:dct`: PASS.
- `npm run typecheck:demo`: PASS.
- `npm run build`: PASS.
- `npm test -- --no-progress`: PASS, 396/396 pruebas.
- `npm run test:demo`: PASS, 50/50 escenarios Playwright.
- `npm run test:odr`: PASS, 3/3 escenarios Playwright específicos: deep-link opaco con recarga y cero escrituras; identidad `..` preservada por codec de ruta; cierre dedicado a 320 px sin controles ajenos ni overflow.
- `git diff --check`: PASS.
- `npm run lint`: no ejecutable porque el proyecto Angular no define un target `lint`; no se declara como evidencia.
- Verificación posterior: ningún proceso quedó escuchando en el puerto demo `4215`.

## Primera ronda de revisión independiente (`b174d97`)

- Astra producto/UX: REQUEST_CHANGES P2 por deep-link opaco no recargable.
- Astra seguridad: REQUEST_CHANGES P2 por correlación incompleta de la identidad técnica; sin IDOR ni mutación nueva demostrada.
- Sol arquitectura/SDD: REQUEST_CHANGES P2 por correlación incompleta y ausencia de aceptación browser específica ODR.
- Correcciones aplicadas: delimitadores codificados conservados como datos opacos, correlación fail-closed de la cuádruple y suite Playwright ODR dedicada.
- Segunda ronda sobre `9e57931`: arquitectura y seguridad aprobaron; producto detectó el caso residual `.`/`..` normalizado por el navegador.
- Corrección residual: codec reversible `id:<opaco>` en el borde de navegación, con prueba unitaria y Chromium específico. Tercera ronda pendiente sobre nuevo head exacto.

## Reconciliación retrospectiva — 2026-11-14

- PR #57 integró el corte con merge `7382616`.
- Verify alojado `42123918634` terminó verde en sus tres checks.
- PR #61 y merge `40c2880` absorbieron la aceptación acumulada de ODR dentro
  de la paridad productiva; Verify `42581477663` terminó verde en cuatro jobs.
- Reviews acumuladas de bugs, seguridad y UX/producto aprobaron el corte sin
  P0–P3. Los pendientes ODR-R1/ODR-CI eran estado documental residual.
- No se atribuyen a este cierre StoreCore live, fiscal, pagos reales,
  transportistas, deploy ni homologación.
