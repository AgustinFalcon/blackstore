# CLR-T08-C — frontend v2 y journal

Base exacta `fc858de42a5e021aab2f47ab68794a2519ff2cb5`; branch `feat/cash-ledger-frontend-t08c-v2`, worktree nuevo `blackstore-clr-frontend-t08c-v2`. El worktree previo sucio se conservó intacto; sus seis archivos se consultaron selectivamente, sin copiar su recoveryScope inventado ni rutas dentro del dominio.

## Source implementado

PosExecutionContext/PosContextState, AccountingLifecycleState, SaleCommandKind/admission, JournalFamily/Phase y PaymentLedgerSemantics son tipos cerrados con Unknown. PosWireMapper traduce contexto GET v2, lifecycle y admission; JournalDecoder es el borde único de storage, valida versión/tamaño/tipos/UUID/refs/kind y campos de payload. Datos desconocidos/corruptos se conservan, bloquean y producen aviso neutral de cuarentena; no hay botón de olvidar ni purga.

CommandHttp concentra rutas nuevas. CommandRuntimeStore coordina contexto/lifecycle, generación de sesión, actor, preflight de referencias caja/venta y journal. Reserva usa exclusivamente contexto provisionado y concordancia de terminal/caja; IDs del browser sólo identifican intención. Caja/pagos/reserva/commit/release comparten prepare y claim IndexedDB en una transacción readwrite antes del POST; se espera oncomplete. El ámbito es origen real + clientInstanceId/deviceId verificados y referencia de terminal, caja y actor. Storage fallido impide envío. No persiste SID, CSRF ni permisos.

Prepared/AwaitingReceipt tras reload se consultan por familia y mismo commandId. Nuevo SID/mismo actor conserva evidencia y sólo GET; actor distinto no recibe payload/receipt anterior y mantiene aviso/bloqueo neutral. Contexto cambiado/tardío/contradictorio y respuestas de generación anterior no habilitan envío ni resuelven evidencia nueva. Consultas concurrentes del mismo comando/generación comparten GET. Dos tabs no pueden preparar nuevas intenciones simultáneamente; la exclusión se limita al mismo origen/perfil de IndexedDB. Un claim pendiente se puede consultar, nunca expirar para fabricar otro comando.

Accepted es sólo admisión. La fase ReceiptVerifiedAwaitingRefresh conserva el bloqueo hasta GET correlacionado y resolución persistida. Captura verifica identidad/caja/paymentId/medio/importe; reversión verifica refund confirmado; saga verifica estado autoritativo. Caja y egreso verifican entidad caja después del receipt contable; no existe GET de egreso individual en este contrato y no se inventó uno. NotFound/Unavailable/rechazo no liberan incertidumbre ni reenvían. Recuperación sólo GET, también desde el botón explícito de comprobar contexto/evidencia. No queda POST v1 de saga ni feeAmount en capturas; se elimina argumento de fee UI y conserva lectura histórica.

La semántica neta de T08 permite Unpaid con historia después de refund confirmado; durable detail verifica suma captures menos refunds contra saldo y acciones autoritativas. Ticket v2 usa PaymentLedgerSemantics.Net y conserva Legacy/Unknown cerrados para otras políticas; estados terminales/pendientes no generan otra intención desde sus botones.

## Validación y límites

Se añadieron suites de mapper/context/lifecycle/receipt, journal decoder/claim IndexedDB real, coordinator barrier/storage/doble submit/refresh/NotFound/SID/actor/permiso/contexto/lifecycle/cuarentena/respuesta tardía/GET concurrente, semántica neta y componentes. Las suites anteriores que asumían POST v1 o receipt sin journal/refresh se migraron al contrato y puertos v2; se mantienen regresiones de dinero/transiciones y tipos previos.

Antecedentes de validación: `npm run typecheck` y `npm run build` PASS en source intermedio (475.13 kB iniciales). Intentos de Karma unitarios bloqueados antes de assertions por esbuild: `Cannot read directory ../../../../../../..: Acceso denegado`, resolución de polyfills y node_modules junction hacia otro worktree. También hubo intentos de build bloqueados con el mismo sandbox; no se atribuye PASS a esos intentos.

Validación externa comunicada por el coordinador sobre commit exacto `82271775df12d79554aacbfc67030db3843f1445`, en worktree detached con dependencias locales: `npm ci` desde lockfile PASS; `npm run typecheck` PASS; `npm run build` PASS, main 438.69 kB y initial 476.19 kB. Suite completa `ng test` desde unidad temporal `T:` con launcher built-in `ChromeHeadlessNoSandbox`: **161/161 PASS**. El primer intento estándar tuvo bloqueo sandbox/GPU; se resolvió con la unidad temporal y launcher existente, sin cambiar source ni CI. La unidad `T:` fue desmontada; no hubo cambios de código durante la validación. Este commit documental conserva exactamente el source validado.

`npm audit` reportó **7 high** en dependencias preexistentes del lockfile. Hallazgo abierto para security triage: no se ejecutó `npm audit fix`, no se cambiaron versiones ni se infiere explotabilidad o cierre del hallazgo por el PASS de tests/build.

Browser CLR real/backend/PG16/crash/reinicio/CI hospedado y reviews independientes bugs/seguridad/SDD exact-head NOT_RUN/PENDING. El test unitario de claim IndexedDB PASS no equivale a dos pestañas reales end-to-end contra backend/PG16. No se promete exclusión entre perfiles/dispositivos ni recuperación tras pérdida total de storage. Evidencia corrupta u otro actor requiere conciliación operativa y no se borra para desbloquear.

T08-C `implemented_pending_pg16_browser_ci_review`; T08 parcial y T09 planned. ADR-003 conserva revisión específica pendiente sin heredar approvals. Homologation BLOCKED; Publication NOT_RUN. Sin activación, servicios persistentes, deploy, secretos, push/PR ni /sdd.finish. Los procesos de validación propios finalizaron; el junction sólo fue creado dentro del nuevo worktree y no modifica el worktree previo.
