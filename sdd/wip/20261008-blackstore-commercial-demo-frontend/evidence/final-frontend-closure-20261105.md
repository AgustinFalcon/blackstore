# Evidencia de cierre frontend local — 2026-11-05

## Identidad y alcance

- Branch: `codex/split-blackstore-pr49`.
- Código exacto revisado: `15655610745d100f7692e3b65199618b0c0004c4`.
- Alcance acreditado: Demo del POS presencial BlackStore, perfiles ficticios Cajero y
  Encargada, con datos locales y sin IO de negocio.
- Fuera de alcance: StoreCore live, POSC B/T/U/E, PostgreSQL/SID real, facturación,
  Mercado Pago/MODO, Mercado Libre, Correo Argentino, deploy y homologación.

## Resultado funcional

Quedaron cubiertas Inicio, Nueva venta, Pago, Comprobante, Caja, Ventas, Productos,
Inventario, Clientes, Reportes y Escenarios. El corte incluye CRUD simulado,
carrito, descuentos, pagos efectivo/tarjeta/transferencia/QR, pago mixto, reintento y
cancelación, caja y cierres históricos imprimibles, devoluciones, stock, clientes,
filtros, CSV, comprobante no fiscal y perfiles/permisos.

Los estados finitos de rol, medio y estado de pago, estado de venta/caja/producto,
movimiento, escenario, filtro y página permanecen como tipos cerrados con `Unknown`
y traducción única en el borde. `DemoMoney` conserva centavos con `BigInt`, rechaza
overflow antes de publicar estado o consumir secuencias y serializa vista/CSV sin
división flotante.

## Gates exactos

- `npm run typecheck`: PASS.
- `npm run typecheck:demo`: PASS.
- `npm run build`: PASS. Inicial 38.34 kB; lazy Demo 153.05 kB; lazy operacional
  158.76 kB.
- `npm test`: **324/324 PASS**.
- `npm run test:demo:domain`: **80/80 PASS**.
- `npm run test:demo`: **50/50 PASS**.
- `git diff --check`: PASS (advertencias de normalización LF/CRLF, sin errores).

La configuración conserva un script `lint` heredado sin target Angular asociado;
no forma parte del workflow vigente (`verify.yml` usa unitarias y typechecks) y no se
declara falsamente como gate ejecutado.

## Evidencia visual y accesible

Se inspeccionaron las 26 capturas generadas en 1440, 768 y 390 px. El browser gate
agrega reflow a 320 px, navegación por teclado, foco/restauración de menú y modales,
asociación `aria-invalid`/`aria-describedby`, errores por campo, precondiciones de
pago y recuperación. Las capturas finales verifican que la barra fija no tape el
carrito y que producto/cantidad/CTA permanezcan alcanzables.

No se atribuye a esta evidencia una certificación WCAG externa; acredita el conjunto
automatizado y la inspección focal definidos por este SDD.

## Reviews coordinadas

- Astra UX/UI/Product final: **APPROVE; P0=0, P1=0, P2=0, P3=0**.
- Astra arquitectura/seguridad final: **APPROVE; P0=0, P1=0, P2=0, P3=0**.

Durante las rondas se corrigieron: carrito oculto bajo toolbar, pago mixto persistente,
descuento desincronizado, rango de fechas invertido, pluralización, identidad/SKU del
ranking, cierres de caja mutables, sumas monetarias inseguras y pérdida de centavos en
pantalla/CSV. Ambos reviewers verificaron nuevamente los fixes y coordinaron resultados.

## Decisión

El frontend Demo de BlackStore queda **aprobado localmente para presentación al
cliente**. Esta decisión no autoriza merge, deploy ni integración productiva; esos
pasos deben pasar por sus PRs y gates propios.
