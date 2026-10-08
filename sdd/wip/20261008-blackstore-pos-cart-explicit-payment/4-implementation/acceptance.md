# Fixtures y aceptación POSC (todas NOT_RUN)

## Fixtures controladas

Construir catálogo de prueba explícito en DB/puerto fixture, nunca fallback productivo: variante A, SKU A, nombre `Artículo A`, versión P1, precio 10.00, disponibilidad conocida 5; variante B, SKU B, nombre `Artículo B`, versión P2, precio 7.50, disponibilidad 4. Carrito A×2+B×1 = 27.50. Captura parcial 10.00 seguida de 17.50 sólo tras refresh; total de pagos 27.50. Versiones/precios/stock pertenecen a fixture declarada, no prueban StoreCore live.

Variantes de prueba: B stale, B sin stock, catálogo ausente, nombres Unicode, cantidades máximas, total overflow, moneda inválida, medio/estado/versión futuros. Crear staff/caja/contexto propios del harness y roles restringidos; ninguna identidad real. Generar fixtures históricas de journal v1 usando implementación base y guardar golden hash/canonical bytes como referencia inmutable. V1 incluye Reserve/Commit/Release; V2 incluye dos órdenes del mismo carrito. No fabricar hashes de ejemplo ni actualizar goldens por snapshots automáticos.

## Casos obligatorios

- **A01 / POSC-001/006:** una y dos líneas, agregar/eliminar/cantidad y límite 100; repetir variante en wire, cero, fracción, null, campo extra y overflow se rechazan. B inválida revierte A y B, con cero intención/outbox/recibo/ticket nuevo. Total 27.50 desde backend, no petición.
- **A02 / POSC-006/007:** golden Kotlin/TS v1 inmutables y paridad v2 (UTF-8, moneda exacta, motivo); permutación misma hash, cambios semánticos distinto hash; commandId diferente excluido. Versiones desconocidas no se adivinan. Contrato equivocado no admite recibo.
- **A03 / POSC-007:** upgrade real journal v1 Prepared, AwaitingReceipt y ReceiptVerifiedAwaitingRefresh y comandos Reserve/Commit/Release; mantener bytes/IDs/hash y GET v2. Nuevo journal v2 sólo usa ruta v3. Versión desconocida/body inconsistente/storage fallido queda bloqueado, sin delete/rehash/POST.
- **A04 / POSC-002:** reservar no captura ni hace commit; Accepted/Pending/receipt incompleto/proyección sin líneas o total no habilita cobro. Tras Reserved válido, pulsar Cobrar genera exactamente una captura con medio/importe explícitos. Saldo 17.50 tras primer pago, cero sólo después del segundo receipt+refresh.
- **A05 / POSC-002/003:** saldo ausente, Unknown, capture superior a saldo, decimales inválidos o respuesta de otra venta bloquean. Commit sólo con cobertura completa y snapshot elegible; release respeta historial, reversa no inventa historial vacío. No nueva integración de pago.
- **A06 / POSC-004/008:** repetir commandId/body retorna mismo recibo y cardinalidad única; payload distinto/mismo ID y otra ID/misma operación rechazan sin efecto. Snapshot/fingerprint con actor/caja/identidad alterada no se acepta.
- **A07 / POSC-005/008:** SID/CSRF ausente, staff revocado tras espera, roles Unknown/AUDITOR, ownership ajeno, supervisor sin motivo; cierre/pausa concurrentes en ambos órdenes con barreras y dos conexiones. Autoridad tras locks y 404 opaco antes de mismatch; ningún pago/outbox no autorizado.
- **A08 / POSC-004/008:** crash antes de commit (cero hechos), después de commit antes de respuesta (recibo recuperable), después de efecto remoto fixture antes de aplicar (GET misma cuádruple), y después de aplicación. Reiniciar PID propio y reload browser: ticket multiline/total/evidencia intactos, un outbox por kind y reconocimiento único. Cero POST automático del browser.
- **A09 / POSC-004/005:** doble click antes de UUID, dos tabs, logout/login mismo actor nuevo SID, cambio de actor/contexto, respuesta tardía, 404 persistente y refresh fallido; bloquear nuevo intento y no cruzar evidencia. GET no genera writes/dispatch. Error de IndexedDB impide primer POST.
- **A10 / POSC-006/007/008:** PG16 clean install y upgrade poblado desde base; migración/grants runtime/constraints y transacción. Comparar recibos/fingerprints/ledger/outbox previos sin alteraciones; v2 monolínea sigue funcionando y una intención multiline no se interpreta como monolínea. No rollback destructivo ni edits de Flyway existente.
- **A11 / POSC-001/002/005:** navegador real en 360/768/1280 px, teclado completo/foco/labels/anuncios; agregar dos artículos, reservar, cobro parcial explícito, segundo cobro, confirmación y lectura final. Sin `route.fulfill` para acreditar backend/PG; fixture StoreCore se identifica en evidencia. Contrastar request counts con DB y captura visual sin datos reales.
- **A12 / todos:** regresión de venta monolínea/journal/CLR/DCT y rutas actuales, typecheck/build/test; CI y reviews bugs/seguridad/SDD del SHA exacto. Resultados incluyen fuente, comando, duración, assertions y teardown de procesos propios. Tests omitidos/discovery no cuentan como PASS; evidencia fixture no equivale a live/homologación.

## Cierre de cortes

B entrega A01/A02/A06/A07/A08/A10 backend; T entrega A02/A03/A09 con pruebas de mapper/codec; U entrega A04/A05/A11 UI; E acredita flujo combinado A01–A12 sobre el mismo source. Los resultados parciales no se atribuyen a otro SHA. Incumplimiento de compatibilidad v1 bloquea T/U/E. Este fichero sólo especifica pruebas, no declara ninguna ejecutada.
