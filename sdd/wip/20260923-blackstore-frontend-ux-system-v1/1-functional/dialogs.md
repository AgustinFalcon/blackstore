# Dialogos (DS-04)

Card max ~28rem, overlay muted, primary + cancel. Destructivo = danger. Nunca secretos ni PAN.

| ID | Título | Cuerpo | Primary | Cancel | Quién |
|---|---|---|---|---|---|
| DLG-CLOSE | Cerrar sesión de caja | Declara el efectivo. La apertura no se edita. Motivo obligatorio. | Cerrar sesión | Seguir en caja | CASHIER de la sesión / SUPERVISOR |
| DLG-EXPENSE | Registrar gasto | Categoría, importe Efectivo, razón. Append-only. | Registrar | Cancelar | CASHIER sesión OPEN |
| DLG-REVERSE | Reversar cobro | Referencia el pago original. Motivo obligatorio. No borra el ticket. | Reversar | Cancelar | CASHIER / SUPERVISOR |
| DLG-RELEASE | Liberar reserva | Devuelve stock del simulador. Misma cuádruple. | Liberar | Cancelar | CASHIER |
| DLG-COMMIT | Confirmar venta | Registra la venta local. El ticket del mostrador ya se facturó. Esta pantalla no emite. | Confirmar | Cancelar | CASHIER |
| DLG-SC-BLOCKED | StoreCore bloqueado | El conector HTTP no está autorizado. El simulador no es evidencia live. | Entendido | — | cualquiera |
| DLG-STOCK | Sin stock vendible | `INSUFFICIENT_STOCK` + availableQuantity. Nuevo `X-Operation-Id`, mismo `X-Sale-Id`. | Entendido | — | CASHIER |
| DLG-STALE | Catálogo vencido | `validUntil` pasado o fixture unavailable. | Volver al catálogo | — | CASHIER |
| DLG-FISCAL | Ticket ya emitido | El mostrador ya facturó al emitir el ticket. BlackStore registra la venta y no la frena. | Entendido | — | CASHIER |
| DLG-KILL | Entitlement disabled | Companion no habilitado. Sin writes. | Salir | — | cualquiera |
