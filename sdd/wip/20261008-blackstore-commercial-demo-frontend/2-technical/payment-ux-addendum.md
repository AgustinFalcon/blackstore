# Pago y mostrador demo — incremento 2026-10-09

Implementación autorizada para la demostración comercial aislada. No invoca APIs,
sesiones, procesadores, conectores o stock externo.

## Comportamiento

- Medios cerrados: efectivo, tarjeta, transferencia y QR; valores desconocidos no cobran.
- Intento cerrado: pendiente, aprobado, rechazado, cancelado y desconocido. Efectivo se
  aprueba al validar recibido; los otros medios esperan una decisión explícita del terminal simulado.
- Ningún intento pendiente, rechazado o cancelado modifica ventas, inventario ni caja.
- Cobro mixto registra efectivo y el saldo electrónico en el mismo comprobante. Sólo
  la parte en efectivo mueve caja, incluyendo devolución. Toda la venta se confirma una vez.
- Cancelar o rechazar conserva carrito, cliente y descuento; reintentar genera un intento nuevo.
- Mientras existe un intento pendiente, el carrito está protegido y puede retomarse al volver.
- Recibo explica entrega inmediata en mostrador y conserva referencia y composición del pago.
- Mostrador <=900 px ofrece búsqueda compacta fija y acceso al carrito; ilustraciones
  locales diferencian familias y variantes sin dependencias externas.

## Gates del incremento

Domain: efectivo aprobado; cada electrónico pendiente→aprobado/rechazado/cancelado;
reintento; idempotencia; mixto y devolución; desconocidos; bloqueo de edición pendiente.
Browser: medios, decisiones, preservación, recibo, 390/768/1440 px, foco/labels y
ausencia de HTTP de negocio. Review UX/producto independiente antes de publicación.
