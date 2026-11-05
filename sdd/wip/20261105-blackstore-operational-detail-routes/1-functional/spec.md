# Spec funcional

## Problema

El runtime operativo permite cerrar caja dentro de `/caja` y consultar ventas desde `/ventas`, pero no ofrece URLs dedicadas recargables para el cierre ni para el ticket de una venta. Un deep link de más de un segmento tampoco selecciona hoy la superficie operativa.

## Objetivos

1. `/caja/cierre` presenta únicamente selección, revisión y confirmación del cierre existente. No duplica reglas, journal ni comandos.
2. `/ticket/:saleId` muestra un snapshot durable de sólo lectura usando las consultas ya autorizadas de ventas y operación.
3. Abrir, recargar, navegar atrás/adelante o cambiar de identidad nunca genera POST, reserva, pago, commit ni release.
4. Los deep links arrancan la superficie operativa, pasan por sesión/guard y conservan ownership/RBAC del backend.
5. Estados de pantalla y disponibilidad son tipos cerrados; valores desconocidos fallan cerrados.

## Criterios de aceptación

- AC-1: las dos rutas existen, tienen `sessionGuard` y permisos mínimos `CashSessionClose` y `SaleRead`.
- AC-2: `/caja` conserva apertura, egresos y cierre; `/caja/cierre` oculta apertura y egresos y reutiliza confirmación/recovery actuales.
- AC-3: el ticket resuelve un `saleId` opaco y case-sensitive sobre páginas autorizadas; cero o múltiples coincidencias no muestran detalle.
- AC-4: una coincidencia única se correlaciona con el detalle por `operationId`; el detalle debe devolver el mismo `saleId`.
- AC-5: cursores inválidos/repetidos, wire incompleto, permisos ausentes, logout, ruta reemplazada o respuesta tardía limpian/fallan sin autoridad residual.
- AC-6: el ticket no expone CTA mutantes; las operaciones continúan exclusivamente en el flujo existente.
- AC-7: 320–390 px mantiene navegación, lectura y formularios utilizables sin pérdida de información.

## No objetivos

No se crea lectura histórica nueva de arqueo. El resultado del cierre sigue apareciendo desde el receipt durable del comando actual. No se trata `saleId` como si fuera `operationId`, no se reconstruyen fórmulas contables en la vista y no se expone información fuera de los filtros de ownership existentes.
