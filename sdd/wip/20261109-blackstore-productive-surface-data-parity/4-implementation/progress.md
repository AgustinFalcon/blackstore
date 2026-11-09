# Progreso

## 2026-11-09 — auditoría y congelamiento inicial

- Base exacta `2b1f60c44ba50c915293f48e2a83554f9ca81943`; worktree limpio creado desde `origin/master`.
- CI master `42446541215` verde. No se hereda como aceptación del nuevo corte.
- Auditoría coordinada GPT-6.1 Sol confirmó nueve rutas POS productivas y once rutas Demo. Demo usa exclusivamente `LocalDemoRepository` en memoria; no es backend ni localStorage productivo.
- Sesión, caja, ventas, detalle y reportes tienen APIs y persistencia PostgreSQL opt-in. El catálogo operativo llama HTTP pero su adapter por defecto sigue siendo fixture; StoreCore live permanece NO-GO.
- BlackStore no tiene ni debe crear storefront comprador en este corte. CRUD de catálogo/inventario/clientes mostrado por la Demo no se promueve sin decidir ownership y contratos separados.
- Specs y plan preparados para review. No hay implementación, activación externa, homologación, PR ni claim de paridad productiva todavía.

## 2026-11-09 — primera review de specs: NO-GO corregido

- Dos reviews GPT-6.1 Sol no encontraron P0/P1 y marcaron P2 de precisión contractual y seguridad.
- Se reemplazó la fuente única por procedencia por dataset y agregación cerrada; el inicio, ticket, ventas, detalle y reportes con evidencia de catálogo quedan `Mixed`.
- Se separaron expectativa del manifest y observación runtime; fixture durable nunca se promociona a real.
- El catálogo conserva su contrato y agrega sólo `source`; no se inventan `active`, stock, imágenes ni categorías. Frescura y fuente son tipos independientes y fail-closed.
- Se congeló el alcance: este WIP no agrega rutas, DTOs ni permisos de ventas/caja/reportes.
- Se incorporaron journey real con reinicio y gates negativos de SID, actor switch, IDOR, Auditor, CSRF, `no-store` y estados Unknown.
- La segunda lectura detectó que la procedencia histórica de productos no existe en los DTO actuales: detalle/listado/reportes quedan `Unknown`, no `Mixed`, y el catálogo actual no se usa para inventarla.
- Se congeló el envelope completo de catálogo (`importedAt`, `validUntil`, `canonicalPath` y `raw*` incluidos); `source` es la única adición compatible.
- La agregación ahora tiene precedencia determinista para `Unknown`, `Unavailable` y `Blocked`.
- El gate browser real se liga al ownership de datasets `BLACKSTORE_*`, no al agregado de la pantalla, por lo que detalle, ventas y reportes `Unknown` siguen obligados a HTTP/PostgreSQL real.
- Re-review coordinada GPT-6.1 Sol: arquitectura/producto GO y seguridad/contratos GO, cero P0–P3. Implementación autorizada.
- `STATUS`, `BACKLOG` y `RELEASE` reconciliados con `master 2b1f60c` y PR #53–#60; historia previa preservada y homologaciones aún bloqueadas.
- Gate ejecutable `verify:surface-manifest` integrado a CI: 10 superficies/9 rutas operativas, vocabulario cerrado, datasets requeridos y agregación verificada.
- Backend agrega sólo `CatalogResponse.source`; conserva envelope/nullability y todos los campos anteriores. Fixture declara `LOCAL_FIXTURE`, loopback `STORECORE_LIVE`, ausencia `UNAVAILABLE`.
- Frontend traduce catálogo en un único mapper runtime a `CatalogSource`/`CatalogFreshness`; Unknown/stale/unavailable bloquean selección y no exponen raw wire.
- Gates enfocados: TypeScript PASS; Angular 24/24 PASS; backend controller/domain PASS. El runner Angular emitió warnings NG0101 heredados pero terminó verde.

## 2026-11-09 — implementación y validación integral

- Las nueve rutas operativas publican evidencia uniforme de superficie y procedencia por dataset mediante `SurfaceDataEvidence`; el manifest ejecutable verifica 10 superficies, 9 rutas y el vocabulario cerrado.
- `CounterContextService` bloquea ventas nuevas cuando la persistencia es desconocida o no durable. La vista no promete operación lista en memoria.
- El mapper de catálogo conserva las referencias opacas V2, incluido U+FEFF, y traduce fuente/frescura exclusivamente en el borde. Los estados finitos continúan modelados como tipos cerrados con `Unknown` fail-closed.
- Inicio, catálogo, ticket, ventas, comprobante, caja y reportes quedaron cerrados sin inventar stock, procedencia histórica ni contratos externos.
- Frontend unitario completo: 414/414 PASS. Build Angular, typechecks y manifest: PASS. Backend completo y `bootJar`: PASS.
- Demo browser: 52/52 PASS. ODR: 4/4 PASS. DCT real: 18/18 PASS sobre PostgreSQL 16, JAR real, Angular, reinicios, SID/CSRF/roles/ownership y recuperación de respuestas.
- Revisión final coordinada GPT-6.1 Sol: Bug APPROVE, Security APPROVE y UX/Producto APPROVE; cero hallazgos P0–P3 pendientes.
- Recursos del DCT detenidos por teardown. El corte queda `release_candidate`; falta únicamente materializar PR, CI remoto y merge en `master`.
