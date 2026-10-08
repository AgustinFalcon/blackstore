# Progreso inicial

## 2026-10-08 — corrección P2 Security posterior a integración T08-D

Source `b0ab8c18ea678e829d2f7dc3ed6f6763335affa3` sobre `65407e2`: CommandHttp preserva status del GET de proyección y safe considera status al recuperar fallos; PosWireMapper exige HTTP200/code200/errorCode null/error ausente o null para Found. Contradictorios conservan journal y cero rePOST. Typecheck/build PASS, Karma 227/227 PASS, audit productivo 0, diff-check PASS. [Evidencia](../evidence/CLR-T08-D-frontend-local-20261008.md). Ningún gate PG16/browser/CI/review se cierra por esta validación local.

## Estado integrado local T08-D — vigente

2026-10-08: rama local `feat/cash-ledger-frontend-t08d`, merge no-ff `5417916` desde backend exacto `bb9e64b176939220c09267993cef9571824b60f2` con frontend exacto `3be9b4a327f1fc9ce8b642bff9a0c8567b9cb072`. Ambos historiales y addenda SDD preservados. Source frontend `b8024dcc0292897aa7a77f96e9ed9e25d63d54ea`: referencia original explícita y reversibilidad por captura no reembolsada; proyección cerrada GET-only, correlación intención/receipt/actor/caja/IDs/snapshot y refresh de contexto/lifecycle antes de Resolved. npm ci/typecheck/build PASS, Karma completo 223/223 PASS y audit productivo 0. C `implemented_pending_pg16_browser_ci_review`; T08 parcial/T09 planned. PG16/browser CLR/crash/restart/CI/reviews de este source pendientes; no homologación, activación ni publicación. Los registros debajo son antecedentes conservados y no sustituyen este estado.

Ver [evidencia local](../evidence/CLR-T08-D-frontend-local-20261008.md).

## Antecedentes conservados — no son estado vigente

## 2026-10-08 — CLR-T08-D documental preparado

Worktree `blackstore-clr-evidence-sdd`, branch `feat/cash-ledger-evidence-sdd`, base backend POS exacta `fc858de42a5e021aab2f47ab68794a2519ff2cb5`. [ADR-004](../2-technical/adr/ADR-004-accounting-command-projection-evidence.md) registra las brechas comprobadas y el contrato: originalPaymentId nullable, refund→capture íntegro/correlacionado, proyección de gasto por commandId cerrada, autoridad/visibilidad actuales y snapshot read-only de receipt/fuentes/postings. Specs/meta/plan/tasks/evidence sincronizados. Sólo SDD; código productivo y migraciones intactos.

T08-D prepared_pending_specific_review, D-BACKEND planned; no GO ni aprobación heredada. C blocked_on_pos_context_and_evidence, T08 parcial, T09 planned. B-POS conserva sus gates previos; PG16/browser/CI/reviews de implementación de este addendum NOT_RUN/PENDING. [Validación documental y límites](../evidence/CLR-T08-D-sdd-20261008.md). Sin activación/deploy/push/PR/homologación/publicación/archivo.

### Antecedente T08-C integrado (conservado)

## Estado vigente único

C `blocked_on_authoritative_read_models`; T08 parcial, T08-D/PG16/browser CLR/crash/CI pendientes. Source 7516b0e: focused5/full194/typecheck/build PASS, audit productivo0. Se conserva 3673658 189/5 FAIL. Hook focus/visible añade invalidación inmediata/GET guardado, nuevos tests NOT_RUN. [Evidencia vigente](../evidence/CLR-T08-C-foreground-20261008.md).

## Historial de cortes y validaciones — no estados actuales

Validación externa exacta 3673658: typecheck/build PASS (483.48 kB), Karma **189 PASS/5 FAIL** y focused integrado **0/5 PASS**, por NG0101 del setup antes del recorrido. Reparado lifecycle de fixture/Zone y separado bootstrap de preflight; assertions integradas conservadas, nueva ejecución pendiente sin npm. [Registro](../evidence/CLR-T08-C-async-reserve-review2-20261008.md). No PASS integrado ni cierre T08.

## Corrección ronda 2 — reserva asíncrona

Sobre 61ea8ad, refresh Reserve usa espera GET-only acotada (20 lecturas/100 ms/deadline 5 s) compartida con AwaitReservationStep. Pending conserva claim; sólo Reserved válido resuelve antes de capture. Unknown/NotFound/timeout/sesión tardía no rePOST/capture. Tests integrados y del objeto común añadidos, ejecución NOT_RUN sin npm. [Evidencia](../evidence/CLR-T08-C-async-reserve-review2-20261008.md). C sigue blocked_on_authoritative_read_models/T08 parcial.

## Correcciones de review C — 2026-10-08

Sobre 0a6bb36: reversibilidad cerrada y fallback tras refund antes de journal; Expense/Settle awaitingRefresh sin falso Resolved; guard por actor/generación después de shareReplay y antes de defer; fingerprint exacto sale-admission-v1 persistido/recalculado/correlacionado y cuarentena. Tests fuente de mapper/store/HTTP/IDB/DOM añadidos, ejecución NOT_RUN por instrucción del coordinador, sin npm. C `blocked_on_authoritative_read_models`; T08 parcial, no usable completo. T08-D read-models backend se formaliza aparte. [Evidencia vigente](../evidence/CLR-T08-C-review-corrections-20261008.md) sustituye estado C previo; validación histórica 161/161 pertenece sólo a source 8227177.

## Validación frontend externa — 2026-10-08

Commit source exacto `82271775df12d79554aacbfc67030db3843f1445`: npm ci local desde lockfile PASS; typecheck PASS; build PASS (main 438.69 kB, initial 476.19 kB); ng test completo PASS 161/161 desde unidad temporal T: con launcher built-in ChromeHeadlessNoSandbox. Primer intento estándar bloqueado por sandbox/GPU; resuelto sin cambios de código/CI y unidad desmontada. [Evidencia](../evidence/CLR-T08-C-frontend-local-20261008.md). C implemented_pending_pg16_browser_ci_review; T08 parcial. Browser CLR/PG16/crash/CI/reviews pendientes; npm audit 7 high preexistentes pendiente security triage, sin audit fix.

## 2026-10-08 — implementación CLR-T08-B-POS

Base exacta `d57cc7a9cb8edccf8a76cbca36eee12a661258ea`, branch `feat/cash-ledger-pos-context-backend`, implementación encargada explícitamente por el coordinador. Contexto cerrado y traductor único, query read-only/GET SID WorkspaceRead no-store, V11 vacío con provisión explícita/evidencia/inmutabilidad/grants, validación nueva Reserve en la misma conexión y lock terminal después de caja antes de venta. Replay autorizado conserva receipt antes de binding actual, incluso cerrado/Paused; comandos históricos no requieren identidad nueva. Fixtures PG16 incluyen upgrade V10 sin backfill, rechazos sin efectos, grants y carreras terminal en ambos órdenes con barreras. [Evidencia](../evidence/CLR-T08-B-POS-backend-local-20261008.md) conserva comandos/resultados reales y bloqueos.

Estado `implemented_pending_pg16_ci_review`; T08-C `blocked_on_pos_context`, T08 parcial, T09 planned. ADR-003/reviews específicas pendientes sin aprobación heredada. No activación, servicios persistentes, deploy, push/PR, homologación ni publicación.

Validación final local PASS: 55 suites/205 tests/0 failures/errors/skips sobre el source final; incluye dominio/HTTP/SID/arquitectura/indisponibilidad y regresión backend no-Docker. JDK21/init externo/offline, build conserva17. PG16 real intentado pero BLOCKED antes de assertions por Docker no disponible; clean/upgrade/races/grants siguen NOT_RUN, fixtures compilan. JSON/diff/links PASS. Teardown: sin procesos Java remanentes ni servicio propio iniciado.

## 2026-10-07 — addendum documental POS

Inspección de base T08-B `39aa9d6dad199d88328e9c3799911e355a9cbddd`: sesión sin dispositivo, workspace desde seed y Reserve nueva sin binding. [ADR-003](../2-technical/adr/ADR-003-pos-execution-context.md) registra solución y límites. CLR-T08-B-POS planned; CLR-T08-C `blocked_on_pos_context`; CLR-T08 continúa parcial. Implementación/migración nueva/PG16/browser NOT_RUN; review específica pendiente. No se heredan aprobaciones T08-A/B. [Evidencia](../evidence/CLR-T08-B-POS-sdd-20261007.md). Este registro sustituye estados planned de C en antecedentes, sin borrar evidencias previas.

## Corte T08-B — backend/schema local, 2026-10-07

Base exacta `6a8a32473f60cd1ab5f9bed33868a07f0f35e918`, branch `feat/cash-ledger-backend-t08b`. Implementa dominio cerrado de comandos y recibos de admisión, fingerprint versionado, puertos/application, admisión JDBC propia de reserve/commit/release v2 con conexión física compartida y sin dispatch HTTP; GET de recibo read-only, query lifecycle separada, permisos y rutas SID/CSRF. V10 es aditiva, append-only, sin backfill, con correspondencia intención/outbox y grants mínimos. Captura rechaza explícitamente campos no soportados, incluido feeAmount null/cero. Recibos contables revalidan permiso del kind original; FEE conserva replay interno OWNER y no se publica.

T08-B `implemented_pending_pg16_ci_review`; T08 sigue parcial, T08-C/T09 pendientes, sin activar runtime ni homologación. [Evidencia y límites T08-B](../evidence/CLR-T08-B-backend-local-20261007.md). Fixtures PG16 compilados, no ejecutados: PG16 no está instalado, PG18 anterior está detenido; no se levantan servicios ni fuerzan sockets. Clean/upgrade reales, concurrencia/crash/reinicio, CI y reviews finales exact-head permanecen NOT_RUN/PENDING. Aprobaciones del SDD no acreditan implementación.

## Estado agregado T08-A — 2026-10-07

Documentación preparada en worktree `blackstore-clr-contracts-t08a`, branch `feat/cash-ledger-contracts-t08a`, desde exact `9e6e2cb0522bf2e001245a1593edeea2bb3136a6`. [ADR-002](../2-technical/adr/ADR-002-sale-v2-lifecycle-command-journal.md) propone saga v2/recibo Accepted separado de COMMITTED, lifecycle GET y permiso de lectura cerrado, journal durable previo al POST con rehidratación por actor/ámbito/tab, rechazo explícito de feeAmount y pruebas de autoridad/replay/crash. Se actualizaron specs, plan, tareas, metadatos y trazabilidad. Sin código productivo ni migración nueva; backend/frontend actuales conservan las brechas documentadas.

T08-A done: GO DE DISEÑO autorizado explícitamente por el usuario y APPROVE documental/Security sobre exact head `0b4cddc7f26ec6965ad81443ec4cde74aadd2ce5`, según registro del coordinador. Este amend anota el GO sin cambiar contratos ni atribuir nueva review al SHA resultante. T08-B/C planned; T08 sigue parcial y T09 planned. [Evidencia T08-A](../evidence/CLR-T08-A-contracts-20261007.md) conserva alcance y validación. Implementación/runtime/activación/homologación NO aprobados; tests nuevos/PG16/browser/CI/reviews de implementación todavía NOT_RUN/PENDING. Runtime operativo permanece PRE_ACTIVATION; sin activación, push, PR, publicación, archivo ni `/sdd.finish`.

## Evidencia y progreso anteriores (conservar como antecedentes)

2026-10-07: corte de lectura UI CLR-T08 preparado en `blackstore-clr-ui-t08`, branch `feat/cash-ledger-ui-t08`, base exacta `ba1104273a56aa39ba393e20c1521d174d4aaeb1`. Reemplaza la pantalla de sumas v1 por GET v2 SHIFT/DAY con caja/fecha/zona/filtros explícitos, tipos TS cerrados, traductor único `PosWireMapper`, importes exactos y cobertura nullable, cutoff/as-of/snapshot/zona histórica, arqueo Balanced/Shortage/Overage/Unavailable y Unknown neutral. Dos slots independientes cancelan la consulta anterior y verifican generation + epoch antes de cualquier callback; edición de filtros, cambio de caja/identidad y destrucción limpian datos. El interceptor SID ahora cubre `/api/v2` y conserva cookie/CSRF y descarte por identidad.

Validación T08 local: typecheck y Angular build PASS; compilación Angular de specs y runner temporal Vitest 4.1.11/jsdom, 6 suites/37 tests PASS (20 nuevos + 17 de mapper/SID existentes). Pruebas DOM de labels/fieldset/aria-busy/status/alert, no auditoría a11y browser completa. El comando normal `npm test` falló en bundling Karma por acceso denegado/resolución absoluta de archivos bajo Windows antes de ejecutar assertions; se conserva como fallo de entorno y no se afirma PASS Karma. Detalles/reproducción en `evidence/CLR-T08-local-20261007.md`.

T08 continúa parcial: este alcance sólo entrega lecturas/arqueo UI; no implementa UI de recibos de comandos ni migración total de mutaciones internas a v2. CLR-T09 sigue planned. PG16, browser SID + backend exacto, reinicio, aceptación CLR-010, a11y browser, CI hospedado y reviews exact-head siguen NOT_RUN/PENDING. Runtime PRE_ACTIVATION; sin activación, homologación, publicación, push, PR ni merge.

2026-10-07: CLR-T07 implementó las lecturas SHIFT/DAY en el worktree separado `blackstore-clr-reads-t07`, base exacta `8322fa715e2201a3b8f3bb3cb1a4d451f574c401`. Snapshot PostgreSQL único, autoridad actual SID, zona efectiva histórica/versionada, intervalos/DST, filtros titular/terminal/caja, cobertura desde fuentes originales, movimientos separados de recognition, fórmulas y métricas sin evidencia nullable. Validación focal 4 suites/24 tests/0 failures/errors/skips (unitarios, MockMvc y arquitectura); fixture PG compilado pero no ejecutado. PostgreSQL local bloqueado por restricted token Windows 87/3 del sandbox; PG16, snapshot concurrente real, dataset SQL, browser, CI y reviews exact-head continúan NOT_RUN/PENDING. Evidencia `evidence/CLR-T07-local-20261007.md`. No done, activación ni gate externo nuevo.

2026-10-07: kit SDD creado para TODO-007 a partir del plan Astra y fuentes locales.

2026-10-07: primera revisión Astra/Sol/Security emitió NO-GO por terminalidad tras reversión, legado, devengo inmutable, cobertura DAY y permisos/contratos. Se resolvieron con reversión íntegra por pago y saldo neto, RELEASE_PENDING→RELEASED durable, `expense_settlements` append-only, universo de cobertura independiente de postings, zona efectiva versionada y contratos/permissions de command receipts. Una segunda revisión detectó y corrigió circularidad RELEASE/restitución y precisó que “parcial” es respecto de una venta split, no del importe de un pago.

Estado active; GO de diseño aprobado por Astra funcional/arquitectura, Sol factibilidad/Bugbot y Sol seguridad. CLR-T01 done; CLR-T02..T09 planned, 1/9 done.

Se especificaron fórmulas de efectivo/flujo comercial, cierre sin ajuste automático, eventos y resultados cerrados, V8 aditiva, command receipts, reconocimiento COMMITTED separado, locks caja→venta→delivery, snapshots SHIFT/DAY y LegacyIncomplete. Son decisiones de diseño aceptadas, no afirmaciones de implementación.

Código y migraciones: NOT_RUN. Los escenarios CLR de PG16 clean/upgrade, atomicidad/crash/concurrencia/seguridad, browser/reinicio y rollback/kill switch: NOT_RUN. Reviews y GO de diseño: PASS. La infraestructura DCT base quedó integrada en PR #28 (`bca02bf`, merge `b8ce3b1`) con Bugbot y Security APPROVED, CI PR `37657282222` 3/3 SUCCESS y CI posmerge `37658142335` 3/3 SUCCESS. No deploy ni homologación productiva como parte de CLR-T01.

2026-10-07: CLR-T02/T03 quedaron implementadas en el PR apilado #30, con reviews exact-head aprobadas y pruebas locales; el CI alojado continúa bloqueado por billing/spending de la cuenta GitHub, por lo que no se marcaron `done` ni se activó el runtime.

2026-10-07: CLR-T04 quedó implementada sobre `77c69125469be6dacc5a68729436a8d33d8ffefd`. Incluye commands y receipts v2, apertura, captura, reversión total, fee OWNER, devengo/liquidación de egresos, atomicidad, locks caja→venta→delivery, autorización post-lock, opacidad, replay/mismatch, selección de semántica por lifecycle y traducción estructurada de `original_payment_id`. La vista y las acciones comparten el mismo tipo cerrado `PaymentLedgerSemantics`; `PAUSED` conserva lecturas v2 y bloquea admisión de writers por separado.

Evidencia local T04: PostgreSQL 18, 7 suites, 45 tests, 0 failures/errors/skips; regresión real `LocalSaleSagaService` + autorización JDBC + `JdbcCounterEntryStore` + `JdbcSaleRecordStore` + runtime ACTIVE/PAUSED llega a RELEASED. Bugbot, Security y SDD/SOLID emitieron APPROVE sin P0–P3. Detalle en `evidence/CLR-T04-local-20261007.md`.

Estado: CLR-T02/T03/T04 `implemented_pending_ci`, no `done`. PostgreSQL 16, crash/reinicio, T05/T06, browser y homologación siguen `NOT_RUN`/pendientes. Runtime contable permanece PRE_ACTIVATION; no se activaron StoreCore, fiscal ni publicación.

2026-10-07: CLR-T05 quedó `implemented_pending_ci` sobre la base apilada T04. Implementa cierre v2 y snapshot durable Balanced/Shortage/Overage/Unavailable sin ajuste, terminalidad fail-closed con traductor único de evidencia remota, reconocimiento COMMITTED atómico sólo en ACTIVE, fence durable de lifecycle y admisión separada Legacy/V2/Worker. PRE_ACTIVATION conserva la terminalización legacy sin crear hechos v2; ACTIVE rechaza intención/commit/release v1; PAUSED bloquea nuevas admisiones v2 y worker.

Validación local final T05: PostgreSQL 18, 12 suites, 69 tests, 0 failures/errors/skips. Barreras SQL independientes acreditan pago/reversión/egreso/intención/worker-reconocimiento contra cierre, ambas órdenes relevantes, autoridad revocada durante espera del lock, replay/mismatch, rollback y opacidad. R4 Bugbot, Security y SDD/SOLID GPT-6.1 Sol: APPROVE sin P0–P3. Detalle en `evidence/CLR-T05-local-20261007.md`. PostgreSQL 16, crash/reinicio y rollback binario T06 permanecen NOT_RUN; CI hospedado y merge siguen pendientes. Runtime permanece PRE_ACTIVATION y homologación/publicación BLOCKED.

2026-10-07: CLR-T06 implementó un arnés v2 de proceso propio para apertura, egreso y cierre, con límites `BeforeCommit`/`AfterCommit`, credencial runtime efímera por entorno, verificación de commit antes de exponer la barrera poscommit y eliminación acotada al `Process` creado. El reinicio usa una instancia JDBC nueva, consulta el receipt con el mismo `commandId`, repite el mismo payload y comprueba una sola fila de receipt/auditoría y el conjunto completo de hechos. El caso de commit confirmado con pérdida de respuesta retorna `Unavailable`, se recupera por receipt y no duplica postings.

Validación local T06: PostgreSQL 18, `AccountingMutationPostgresTest` 22 tests, 0 failures/errors/skips; dentro de ella, 12 escenarios crash v2 (apertura, egreso, captura, reversión y reconocimiento worker, según corresponda, más cierre; límites pre/postcommit), commit incierto y traductores de tipos cerrados/Unknown. El intento de suite global ejecutó 223 tests: 199 pasaron y 24 no iniciaron o fallaron por ausencia de Docker/Testcontainers; no se atribuyen como regresiones. La ejecución usó temporalmente JDK 21 porque el host no expone JDK 17, restaurando el build a Java 17 después. PostgreSQL 16 clean/upgrade, rollback de binario anterior y CI hospedado siguen `NOT_RUN`; CLR-T06 no está `done` ni autoriza activación/homologación.

2026-10-07: CLR-T07 quedó `implemented_pending_pg16_ci_review` sobre T06. Agrega lecturas v2 SHIFT/DAY tipadas con autoridad revalidada, snapshot PostgreSQL `REPEATABLE READ` read-only, cutoff y zona histórica versionada; universo de cobertura independiente, filtros previos a agregación, totales por medio, fórmulas cerradas e incompletitud explícita. La validación local ejecutó 24 tests focalizados en 4 suites, sin fallos; el fixture PostgreSQL compiló pero no se ejecutó porque Windows sandbox rechazó `initdb`/`pg_ctl` con restricted-token error 87/3. PostgreSQL real, PG16, CI, browser y reviews exact-head permanecen pendientes. Evidencia: `evidence/CLR-T07-local-20261007.md`. Runtime sigue PRE_ACTIVATION; sin homologación ni publicación.

2026-10-07: CLR-T08 agregó operaciones UI v2 para apertura/cierre/egreso/captura/reversión y lectura de recibos correlacionada por commandId estable, sin POST automático; Unknown/NotFound conservan bloqueo y PAUSED/legacy/NotActivated fallan cerrados. Dominio cerrado, mapper único, store compartido y arqueo validado. T08 permanece parcial: reserva/commit/release aún sólo tienen contrato v1, lifecycle no se publica en workspace, harness DCT conserva pruebas legacy y recarga browser/reinicio requiere aceptación. Ver evidence/CLR-T08-operations-local-20261007.md. Typecheck/ngc y regresión Vitest/jsdom locales PASS; PG16, browser, CI y reviews exact-head pendientes. Runtime PRE_ACTIVATION, homologación/publicación bloqueadas.
# Incremento T08-C — 2026-10-08

Source frontend implementado sobre base B-POS `fc858de42a5e021aab2f47ab68794a2519ff2cb5`, con todos los POST comerciales afectados en v2, contexto/lifecycle preventivos y journal común confirmado antes de enviar. Rehidratación conserva incertidumbre y sólo GET; receipt necesita refresh y resolución durable. [Evidencia](../evidence/CLR-T08-C-frontend-local-20261008.md). C `implemented_pending_pg16_browser_ci_review`, T08 parcial. Validación local externa source 82271775df12d79554aacbfc67030db3843f1445: 161/161 unitarios, typecheck/build PASS; PG16/browser CLR/crash/CI/reviews exact-head pendientes. Los estados bloqueados debajo son antecedentes; sus gates externos continúan pendientes.
