# Requisitos POSC

## POSC-001 — Carrito de una venta

El cajero selecciona variantes del catálogo autorizado ya leído, agrega cantidades enteras positivas, edita o elimina líneas antes de reservar. Una variante aparece una vez; agregarla nuevamente suma cantidad con control de overflow. Lista vacía o mayor a 100 variantes bloquea reserva. Mostrar SKU, nombre, cantidad, precio autorizado, subtotal y total con dinero decimal exacto. Un catálogo ausente/inválido/desactualizado bloquea nuevas reservas: no reemplazarlo por valores de muestra. Stock desconocido se muestra como desconocido; la UI no garantiza disponibilidad y el backend decide.

El carrito local previo al envío es un borrador, nunca una reserva ni un asiento. Reservar genera una sola identidad completa y un solo comando multiline. Una línea inválida rechaza la venta completa, sin reservas ni tickets parciales. El payload se congela al preparar el journal; desde ese momento no se edita ni sustituye silenciosamente.

## POSC-002 — Reserva y cobro son decisiones distintas

Reservar sólo prepara/admite reserva. Después de recibo válido, leer venta autoritativa hasta comprobar reserva/evidencia/total; Accepted o Pending no habilitan pago. El operador pulsa una acción explícita de cobro con medio e importe visibles; reserva jamás captura ni confirma automáticamente. El saldo proviene del backend, no del subtotal estimado del borrador.

Reusar medios cerrados Cash/Card/Transfer/Other y Unknown no seleccionable. Son registros del cobro presencial conforme al contrato vigente, no integración con terminal/adquirente ni Mercado Pago. No inventar aprobación externa. Split se realiza como capturas explícitas sucesivas; cada una exige recibo correlacionado y refresh de saldo antes de la siguiente. Sin fee, vuelto, descuentos ni medios nuevos en este corte. Importe positivo y no mayor al saldo exacto; errores de moneda/rango no se redondean.

## POSC-003 — Finalización y cancelación

Confirmar venta es una acción explícita sólo con cobertura autoritativa completa y transición habilitada. Un recibo de admisión de commit no significa venta final: esperar lectura COMMITTED válida. Cancelar borrador sin journal sólo descarta borrador. Liberar una reserva exige decisión vigente y confirmación explícita; un historial de pagos, aun neto cero, no se trata como ausencia de pagos. Reversiones conservan la política CLR existente; no añadir devolución posventa ni ampliar release.

## POSC-004 — Incertidumbre y recuperación visibles

Reload, pérdida de respuesta, cierre de tab, logout, cambio de actor/contexto y reinicio conservan IDs/cuerpo/hash/versión. Recuperación consulta evidencia del mismo comando; nunca POST automático ni nuevo UUID por timeout/404. Unknown, proyección incompleta, error de storage o evidencia contradictoria bloquean siguiente paso con mensaje neutral y acción de consulta cuando esté autorizada. El usuario ve pendiente/recuperación/conciliación, nunca éxito deducido por timeout.

## POSC-005 — Autoridad y accesibilidad

UI y handler usan la misma política tipada; backend revalida SID, CSRF, permiso, caja, lifecycle y contexto bajo locks. AUDITOR, rol Unknown, caja ajena no autorizada o contexto ausente no generan intención. Cambiar sesión invalida respuestas tardías y no muestra evidencia del actor anterior. Teclado, labels, foco tras agregar/quitar, errores asociados, anuncio de cambios y layout sin overflow a 360/768/1280 px son aceptación obligatoria. Conservar sistema visual BSUX y ruta existente; no crear un segundo POS paralelo.
