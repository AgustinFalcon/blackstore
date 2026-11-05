# Evidencia POSC-U — carrito y cobro explícito

Fecha: 2026-11-05. Rama: `codex/blackstore-posc-u`.

Corte local revisable: `24dfea1` sobre `c0314f8`.

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
- `npm test`: PASS, 356/356.
- `npm run build`: PASS.
- Pruebas de integración reales con runtime, journal y HTTP: Pending→Reserved, timeout acotado, 404/null y cambio de actor conservan el claim correcto y nunca capturan automáticamente: PASS.
- Prueba de integración de UI: reserva V3 multiline produce cero capturas automáticas; una captura aparece sólo después del gesto explícito y del refresh correlacionado: PASS.

## Límites honestos

- El browser real combinado con backend/PostgreSQL, restart, dos tabs y screenshots de 360/768/1280 pertenece a POSC-E y todavía no se acredita aquí.
- El rechazo terminal por línea no habilita editar/reintentar: el runtime actual conserva el journal en recuperación Unknown. Hasta incorporar resolución terminal durable, la UI falla cerrada y sólo permite consulta.
- No acredita StoreCore live, stock real, Mercado Libre, fiscal, homologación ni publicación.
