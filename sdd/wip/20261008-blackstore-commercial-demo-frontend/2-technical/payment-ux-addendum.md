# Pago y mostrador demo — PR49

Incremento sobre PR48 final `f9c557b6ec21ef0216dbd4e4e3b926c5ee4ac5f1`.
La política de permisos, calendario comercial, prorrateo entero, restauración de stock
y bootstrap aislado aprobados en los cortes anteriores siguen siendo la base.

## Contratos

- DemoPaymentMethod agrega QR. DemoPaymentStatus cierra Pending, Approved, Rejected,
  Cancelled y Unknown. El tipo decide qué resolución admite un estado pendiente;
  terminales son inmutables. DemoWireMapper es el traductor de controles/query.
- Efectivo se aprueba después de validar recibido. Tarjeta, transferencia y QR
  esperan una resolución explícita del terminal simulado. Sin HTTP de negocio.
- Pending, Rejected y Cancelled no alteran ventas, stock ni movimientos de caja.
  Cancelar/rechazar conserva carrito, cliente y descuento. Reintentar usa otro ID.
- Un commandId conserva fingerprint de medio, recibido, referencia, efectivo y
  contexto de carrito/cliente/descuento. Repetir el mismo intento devuelve su
  resultado; reutilizarlo con contenido distinto falla sin publicar.
  Garantía exclusiva del adaptador en memoria: recargar reinicia la demostración.
- Una aprobación revalida CheckoutFlow y los permisos actuales. Un descuento
  pendiente de Encargada no puede aprobarse después de cambiar a Cajero.
  Cancelar sigue disponible; recuperar Encargada o cancelar y vaciar/rearmar
  permite una recuperación explícita.
- Cajero conserva los permisos de apertura, movimientos y cierre de caja de PR48.
  No recibe permiso de descuento, devolución, catálogo ni ajustes de inventario.
- Mixto mueve sólo cashAmount en caja; el reporte separa efectivo y saldo
  electrónico. Vuelto no cuenta como ingreso. Los importes son centavos seguros.
- Devolver prevalidará efectivo disponible y StockRestorationPlan completo antes
  de publicar o asignar eventos. Nunca deja caja negativa ni stock inseguro;
  una falla conserva comprobante, stock, caja y secuencia.

## Presentación y accesibilidad

Terminales y comprobantes llevan identificación demo/no fiscal, referencia y
composición del pago; no solicitan datos de tarjeta o credenciales.
QR es una ilustración SVG local sin payload ni enlace de pago. Clientes de semilla
usan example.invalid y el formulario pide información ficticia.

Ilustraciones locales, búsqueda compacta sticky y carrito rápido permiten operar
en 390/768/1440 px. Foco explícito al terminal y a recuperación, radios etiquetados,
activación por teclado, Escape, drawer con foco contenido y skip-link conservando
query/ruta tienen cobertura browser. Stock bajo comparte DemoStockFilter en
contador, lista y query; Unknown produce conjunto neutral.

El servidor de tests conserva el globalSetup de PR48 que termina su propio proceso
hijo. No se trasplanta el teardown HTTP histórico.
