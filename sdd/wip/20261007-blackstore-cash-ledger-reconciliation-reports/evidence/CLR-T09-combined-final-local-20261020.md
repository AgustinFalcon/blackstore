# CLR-T09 — validación local combinada final (2026-10-20)

> Antecedente externo: conservado en el snapshot
> `115fad53006b55d27b021883bf49153a3de050fa`. Incluye POSC/Demo y no acredita
> el corte separado #45 ni transfiere sus PASS o APPROVE.

## Alcance y fuente

- Worktree: `blackstore-demo-payment-ux`.
- Rama: `feat/demo-payment-ux`.
- Base versionada observada: `863c9f0d4a52eb48f7470fa138c9a7bfa059afd1`.
- Fuente validada: working tree local combinado; no se le atribuye un SHA porque conserva cambios sin commit.
- StoreCore live, fiscal, Mercado Pago, Correo Argentino y publicación continúan fuera de alcance y deshabilitados.

## Correcciones de esta ronda

1. El DCT usaba el terminal recién insertado en lugar del `T-1` provisionado y apagaba el worker por defecto. `DctDatabase.terminal()` vuelve a la identidad provisionada y `runtimeFixture` activa el worker salvo el escenario `RELEASE_PENDING` que lo desactiva de forma explícita.
2. El adaptador StoreCore fixture dejó de emitir una expiración fija ya vencida; usa el `Clock` inyectado y una vigencia relativa de 15 minutos.
3. La reversa valida un `ReversalReason` normalizado de 3..500 caracteres antes de abrir confirmación, preleer o persistir el journal. Un rechazo local no puede dejar una intención durable sin receipt.
4. Los reportes demo distribuyen el redondeo del descuento por mayor resto y desempate estable, de modo que la suma por categorías reconcilia exactamente con `sale.total`.
5. Se conservan las correcciones anteriores: fondos de caja antes de devolución, foco de feedback accesible, fecha comercial Argentina y carga explícita de sus specs.

## Resultados reproducibles

- `backend\\gradlew.bat test --no-daemon`: **PASS**, 69 suites / 291 tests / 0 failures / 0 errors / 0 skipped; PostgreSQL/Testcontainers real; 11m02s.
- Backend focal + `bootJar`: **PASS**.
- `npm run typecheck` y `npm run typecheck:dct`: **PASS**.
- `npm run test:demo:domain`: **PASS 32/32**.
- `npm test` desde unidad temporal corta `T:` con `ChromeHeadlessNoSandbox`: **PASS 278/278**.
- `npm run build`: **PASS**; initial 507.72 kB raw / 130.20 kB estimado, demo lazy 117.85 kB / 22.43 kB.
- `npm run test:demo`: **PASS 33/33** en Chromium.
- `npm run test:dct`: **PASS 17/17** en PostgreSQL 16 + JAR + Angular, 13.9m, antes de las dos correcciones finales acotadas.
- DCT crítico posterior a `ReversalReason`: **PASS 1/1**, reserva + captura CASH/CARD + reversas por componente + release, 1.2m.

## Fallos de entorno conservados

- El primer `npm test` desde la ruta larga falló durante bundling con `Acceso denegado`/resolución absoluta, antes de ejecutar assertions. Se repitió desde la unidad temporal corta `T:` y pasó 278/278; `T:` fue desmontada.
- El primer `npm run test:demo` no pudo limpiar su propio directorio de resultados por `EPERM`; la misma orden con permiso de escritura pasó 33/33.
- El primer DCT de esta ronda mostró `PENDING_RESERVATION` porque el fixture apagaba su worker. Tras corregir el default, el escenario crítico y la matriz completa pasaron.

## Reviews exactas del working tree

- GPT-6.1 Astra técnica/Bugbot: **APPROVE**, sin P0–P3.
- GPT-6.1 Astra Security: **APPROVE**, sin P0–P3; SID/CSRF, autoridad, journals GET-only, workers, V12 y grants revisados.
- GPT-6.1 Astra Producto/UX: **APPROVE**, sin P0–P3; centavos, reversas, fondos, foco, fecha Argentina y runner revisados.
- Los dos P2 de la primera ronda (motivo corto y suma por categoría) fueron corregidos y re-revisados antes de estos APPROVE.

## Recursos y límites

- Cada DCT destruyó su Angular, JAR y contenedor PostgreSQL 16; no quedaron contenedores DCT.
- La unidad `T:` fue desmontada.
- Docker Desktop y Gradle se detienen al terminar esta sesión de validación.
- CI hospedado, commit, push, PR y merge: **NOT_RUN/PENDING**.
- Este PASS no autoriza integración live, homologación, datos reales ni publicación.
