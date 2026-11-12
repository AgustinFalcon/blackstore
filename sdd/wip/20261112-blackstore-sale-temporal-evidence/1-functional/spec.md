# Especificación funcional

## Problema

Las superficies `/ventas` y `/ticket/:saleId` muestran `Fecha: No disponible`
aunque PostgreSQL conserva cuándo nació la venta, cuándo cambió por última vez y
cuándo se registró cada pago. La persona operadora no puede ubicar una venta ni
un cobro en el tiempo durante una demo o una consulta real.

## Resultado esperado

1. El listado muestra la fecha real de creación de cada venta.
2. El detalle y el comprobante muestran creación y última actualización de la
   venta como conceptos distintos.
3. Cada pago muestra su propia fecha de registración.
4. Las fechas se presentan en la zona configurada de la instalación/navegador,
   pero el wire conserva instantes UTC ISO-8601.
5. Un valor ausente, inválido o futuro incompatible se rotula `No disponible`;
   nunca se imprime el wire crudo ni se inventa la hora actual.
6. La ausencia de fecha no habilita comandos ni modifica la validez contable de
   una venta. Es evidencia de presentación, no autoridad de transición.

## Aceptación

- Venta y pagos sobreviven reinicio y conservan exactamente sus instantes.
- Lista, detalle y ticket muestran fechas reales y diferenciadas.
- Mapper frontend falla cerrado ante null, texto inválido y fecha imposible.
- Accesibilidad: las etiquetas dicen `Creada`, `Última actualización` y
  `Registrado`, sin depender sólo del orden visual.
