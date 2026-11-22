# BlackStore ferretería showcase

Estado: ready_to_ship
Fecha: 2026-11-22
Base: master ff22c12

## Objetivo

Reemplazar la demo de indumentaria por una presentación comercial de ferretería
coherente con StoreCore, sin habilitar StoreCore live ni proveedores externos.

## Alcance funcional

- 80 productos ficticios de herramientas, fijaciones, medición, seguridad,
  pintura, electricidad, jardín y consumibles.
- Imágenes de producto para toda tarjeta y fallback local.
- Stock normal, bajo, agotado e inactivo.
- 15 clientes ficticios y 24 ventas históricas, incluyendo venta multilínea,
  medios de pago variados y devoluciones.
- Datos visibles en inicio, POS, productos, inventario, clientes, ventas, caja,
  reportes y escenarios.
- CRUD demo, carrito, cobro, reintento, caja, stock, devolución, filtros,
  exportaciones y responsive continúan ejecutables.

## Límites

- Demo aislada en memoria: no HTTP de negocio, no secretos, no persistencia.
- Mercado Pago/QR, StoreCore live, ARCA, Correo Argentino y homologación externa
  siguen bloqueados/fuera de alcance.
- Los estados y vocabularios finitos conservan tipos cerrados y Unknown.

## Aceptación

1. Catálogo contiene exactamente 80 SKUs únicos y 14 categorías comerciales.
2. Cada producto tiene imagen HTTPS, precio entero positivo y stock no negativo.
3. La UI muestra imagen o fallback y conserva accesibilidad/responsive.
4. Los flujos de demo existentes siguen verdes con el nuevo dataset.
5. Unit, typecheck, build y Playwright demo pasan sobre el head exacto.

## Implementación verificada

- Catálogo único tipado: `ferreteria-demo-catalog.ts`.
- Identidad de comprobantes de runtime separada del rango histórico para evitar
  colisiones (`V-201+` frente a `V-90..V-113`).
- Arqueo de un turno reabierto reinicia el declarado al nuevo fondo esperado.
- Evidencia local: [showcase-evidence.md](4-implementation/showcase-evidence.md).

