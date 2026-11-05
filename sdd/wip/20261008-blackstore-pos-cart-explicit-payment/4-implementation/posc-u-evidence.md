# Evidencia POSC-U — carrito y cobro explícito

Fecha: 2026-11-05. Rama: `codex/blackstore-posc-u`.

Corte de implementación verificado: `54be9eaa13e3c3ee0376e3f61e4a59684ee84eea` sobre base `dbb3c368720fe153c17be09d592ab14aadb14390`.

## Implementación local previa a review

- `/ticket` reemplaza el formulario monolínea por carrito multiline con precio autorizado de solo lectura, cantidades, eliminación, subtotales y total exacto.
- La reserva usa `SaleCommand.createMultilineReserve` V3 y termina al comprobar una reserva autoritativa. No programa ni ejecuta capturas.
- Cada cobro requiere un gesto separado, `PaymentMethod` cerrado, importe decimal exacto, receipt correlacionado y refresh de saldo antes del siguiente.
- Confirmación, liberación, reversa y recuperación conservan las políticas tipadas existentes. Unknown/contradicción bloquean y no generan otro POST.
- Layout específico para 360/768/1280, foco visible, anuncios `aria-live`, labels por producto y estados vacío/cargando/recuperación/completado.
- Los diálogos destructivos enfocan inicialmente Cancelar y usan `box-sizing: border-box` en móvil.
- La operación activa persiste su `operationId` en la URL; reload y reintento abren la identidad durable sin repetir la reserva.
- El historial visible se reconstruye desde el ledger durable y conserva capturas/reversas incluso con neto cero.
- `ValidateCartStep`, `PrepareReservationStep`, `RefreshCoverageStep` y `CommitSaleStep` separan validación, preparación y transiciones del componente.

## Gates ejecutados

- `npm run typecheck`: PASS.
- `npm test`: PASS, 359/359.
- `npm run build`: PASS.
- `npm run typecheck:dct`: PASS.
- Pruebas de integración reales con runtime, journal y HTTP: Pending→Reserved, timeout acotado, 404/null y cambio de actor conservan el claim correcto y nunca capturan automáticamente: PASS.
- Prueba de integración de UI: reserva V3 multiline produce cero capturas automáticas; una captura aparece sólo después del gesto explícito y del refresh correlacionado: PASS.
- DCT Chromium + backend real + PostgreSQL: carrito, reserva V3, cobros explícitos CASH/CARD, historial, reversas sucesivas y liberación final: PASS.
- DCT de recuperación: respuesta de release perdida, backend reiniciado, worker detenido, cierre de caja bloqueado, recuperación GET-only y aplicación terminal única: PASS.
- La reserva V3 atraviesa la misma frontera SID/CSRF que V1/V2; el interceptor elimina autoridad forjada y tiene prueba de regresión específica: PASS.

## Límites honestos

- La matriz visual completa con screenshots de 360/768/1280 y el recorrido cross-tab integral pertenece a POSC-E y todavía no se acredita aquí.
- El rechazo terminal por línea no habilita editar/reintentar: el runtime actual conserva el journal en recuperación Unknown. Hasta incorporar resolución terminal durable, la UI falla cerrada y sólo permite consulta.
- No acredita StoreCore live, stock real, Mercado Libre, fiscal, homologación ni publicación.
