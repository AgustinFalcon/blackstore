# CLR-T08-D — frontend integrado y validación local

## Corrección P2 Security — source y validación vigentes

2026-10-08: source nuevo `b0ab8c18ea678e829d2f7dc3ed6f6763335affa3` parte de HEAD integrado `65407e2a89e311b117bde6b72aa431568a529954`, sin reescribir ramas. El registro original debajo se conserva como antecedente.

CommandHttp.safe considera status real de HttpErrorResponse: bodies de error sin code coincidente no se adoptan como recibos; el GET de proyección usa observe response y conserva status real, incluso en fallos. Parse/interceptor failures con status 2xx quedan neutralizados. PosWireMapper.expenseProjection exige status HTTP 200, code 200, errorCode null y error ausente/null antes de decodificar Found. Un body exitoso no rehabilita HTTP503.

Tests nuevos prueban HTTP503 con body code200 Found y HTTP200/code200 con errorCode UNAVAILABLE: resultan Unknown, conservan ReceiptVerifiedAwaitingRefresh/bloqueo, consult repite sólo GET y una nueva execute no emite POST. Decoder comprueba además error object y errorCode ausente, y el caso válido sin errores. Test de safe rechaza receipt HTTP503 con body Committed/code200. Las tres operaciones válidas y el flujo exacto receipt→Found→refresh→Resolved conservan sus tests.

Validación final sobre ese source: `npm run typecheck` PASS; `npx ng test --watch=false --browsers=ChromeHeadlessNoSandbox --progress=false` **227/227 PASS**; `npm run build` productivo PASS (main 454.93 kB, initial 492.43 kB, transferencia 125.81 kB); `npm audit --omit=dev --json` PASS, 0 vulnerabilidades; diff-check PASS. Primer build/audit de esta ronda bloqueados por red; tras nuevo permiso puntual ambos PASS. Sin desactivar optimización/font inlining; T: desmontado, sin cambios dependencias/config CI.

PG16/browser/crash/restart/CI/reviews sobre source corregido siguen NOT_RUN/PENDING. Sin push/PR/deploy/activación/publicación/homologación. El fix no se adjudica APPROVED hasta revisión específica del nuevo head.

Fecha: 2026-10-08. Worktree `work/blackstore-clr-frontend-t08d`; rama `feat/cash-ledger-frontend-t08d`.
Backend exacto `bb9e64b176939220c09267993cef9571824b60f2`; frontend T08-C exacto `3be9b4a327f1fc9ce8b642bff9a0c8567b9cb072`.
Merge no-ff `5417916` preserva ambos padres/historiales; únicamente seis conflictos SDD, resueltos conservando ambos antecedentes y DAG D-BACKEND/C/T09. Source nuevo validado: `b8024dcc0292897aa7a77f96e9ed9e25d63d54ea`.

## Cambio y evidencias admitidas

- Lectura durable exige originalPaymentId explícito: captura null y refund con referencia positiva en el mismo snapshot de venta, capture íntegra/medio/importe, IDs únicos, sin self/refund→refund ni doble reversión. Evidencia incompleta/contradictoria invalida detail/acciones. Un split con captura A reembolsada mantiene B reversible; el journal sólo acepta refund confirmado del original congelado.
- ExpenseProjectionState y ExpensePostingKind son tipos cerrados. PosWireMapper traduce envelope FOUND/NOT_FOUND/UNAVAILABLE/UNKNOWN, operación, medio, completitud/version, expense/settlement/postings/origen/snapshot a objetos de dominio. Unknown es neutral y no muestra valores crudos.
- ExpenseProjectionPolicy contrasta commandId, operación, caja, actor emisor del journal, expense/settlement IDs, committedAt, conjunto exacto ledgerEventIds, categorías/importes, medio, postings con signo/componentes/origen y settlement íntegro contra intención+receipt. SettleExisting permite actor histórico del devengo, exige actor actual emisor en settlement/postings y referencia de gasto congelada.
- CommandHttp consulta sólo `GET /api/v2/expenses/commands/{commandId}/projection`; 404/503/Unknown preservan journal/bloqueo. Ningún resultado genera POST ni nuevo commandId. Found no resuelve por sí solo: el runtime verifica de nuevo contexto y lifecycle antes de Resolved, bajo actor/generación/epoch/scope/permiso; Paused permite confirmar evidencia histórica pero bloquea siguiente mutación. Cuarentena y foreground invalidation de T08-C conservados.
- Nuevos tests: mapper/policy de tres operaciones, estados/envelopes incompletos/desconocidos, referencia refund, split/importe/medio/duplicados y HTTP GET-only. Integración runtime con CommandHttp real y HttpTestingController acredita Prepared→POST→receipt→Found→refresh→Resolved; 404/503/Unknown→consult GET-only, actor/generación tardía, contexto cambiado y Paused bloqueando nueva escritura. No usa DB/browser backend real.

## Validación ejecutada

`npm ci --prefer-offline --cache ../../npm-cache`: PASS, 434 paquetes instalados del lockfile; sin cambio de dependencias. npm avisó scripts de cuatro paquetes no cubiertos por allowScripts; no se habilitaron globalmente ni se ejecutó audit fix.

`npm run typecheck`: PASS (app y spec). `npx ng test --watch=false --browsers=ChromeHeadlessNoSandbox --progress=false`: PASS, **223/223**, Chrome Headless 153.0.0.0, binario local Playwright chromium-1243. Es Karma unitario/HTTP test doubles, no DCT browser.

`npm run build`: PASS, main 454.65 kB, initial 492.15 kB, transferencia estimada 125.80 kB. Build productivo conservó optimización/inlining de fuentes. `npm audit --omit=dev --json`: PASS, **0 vulnerabilidades productivas**, no afirma audit completo de dev.

Primer build directo falló por esbuild al leer ancestros fuera de sandbox. Unidad temporal T: creada/desmontada en la misma shell permitió compilar; primer intento con T: falló por Google Fonts sin red. Audit sin red falló endpoint. Tras permiso de red, build y audit PASS. Primera Karma intermedia: 207 PASS/2 FAIL, expectativas antiguas de bloqueo de todo split y texto T08-D; ambas ajustadas al comportamiento comprobado, corridas finales 223/223 PASS. No se borran esos fallos ni se atribuye PASS a los intentos bloqueados.

`git diff --check`: PASS previo al commit source. Chequeo SDD JSON/DAG/stats y diff final registrado al commit documental. Unidad T: desmontada, sin servicios persistentes ni procesos ajenos modificados. Build dist/.angular/node_modules ignorados; no cambios de config CI para el sandbox.

## Gates pendientes

C `implemented_pending_pg16_browser_ci_review`; T08 parcial/T09 planned. PG16 real, browser CLR, crash/restart, CI hospedado, revisiones bugs/seguridad/SDD y GO específicos sobre el source integrado **NOT_RUN/PENDING**. No hereda reviews ni approvals por el merge. Homologación BLOCKED; activación/publicación/deploy/StoreCore live/fiscal NOT_RUN. Sin push/PR ni /sdd.finish en este corte local.
