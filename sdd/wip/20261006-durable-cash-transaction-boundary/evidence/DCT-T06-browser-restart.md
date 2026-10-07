# DCT-T06 — browser/reinicio BLOCKED

Actualización 2026-10-07: nuevo harness automatizado Chromium/PG16/JAR y job dct-browser; intento local real bloqueado en creación Docker, cero assertions. Ver DCT-browser-harness-20261007.md y docs/testing/dct-browser-restart.md. Conserva BLOCKED; el antecedente IAB inferior no fue reintentado ni sustituido por un PASS.

Fecha: 2026-10-06. Branch `fix/durable-cash-transaction-boundary`, base `e6a47a32b5f21b9f020fc1f9f23a73a19e5ed4a7` + diff local, sin commit. Resultado: **BLOCKED antes de cualquier assertion browser**, no PASS inferido de mocks.

Se intentó crear una pestaña del navegador integrado para el entorno previsto `http://localhost:4203` (frontend) → backend previsto `127.0.0.1:8083` → PostgreSQL DCT `127.0.0.1:5441/dct_test`. El primer call `cua.createBrowserTab('iab', ..., {visible:true})` respondió `IAB visibility is not supported in a subagent thread`. El segundo call con `{visible:false}` no retornó durante 2027.6 segundos y fue abortado por el usuario. No se confirmó una pestaña creada, ni se ejecutó login, apertura, egreso, cierre, reinicio del backend o GET/reload desde browser. No se iniciaron backend/frontend para este intento. No se repite el browser después del aborto.

La UI quedó a cargo del agente frontend: typecheck PASS, build/test bloqueados por esbuild al recorrer `C:\` bajo ACL del sandbox. El test `CashMutationHttpPostgresTest` sí pasó la matriz SID/HTTP contra PostgreSQL real usando MockMvc; esto no sustituye browser real, red real ni reinicio Spring/GET del recorrido completo. Las seis pruebas de crash/reinstancia JDBC PASS pertenecen a T05 y tampoco cierran T06.

Se cerró todo recurso confirmado propio: PostgreSQL PID `70556` mediante pg_ctl fast, listener `5441` ausente, procesos hijos de crash ausentes. No hubo backend/frontend propios ni build activo al teardown. No hay screenshot/browser/network artifact de assertions porque no se alcanzó esa etapa. Los destinos/build mencionados son previstos, no tráfico demostrado. Gate Integration browser `BLOCKED`; CI hospedado y revisiones finales `NOT_RUN`; publicación no ejecutada. No `/sdd.finish`.
