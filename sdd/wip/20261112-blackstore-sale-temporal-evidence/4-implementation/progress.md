# Progreso

## 2026-11-12 — diseño

Auditoría sobre `master 1f16522` confirmó que el core local de Fase 1 no tiene
P0/P1 abierto. El siguiente gap observable es temporal: tres superficies muestran
una ausencia fija aunque V1 conserva evidencia durable. Se aprobó un slice sin
migración ni integraciones externas, con tipos cerrados y traductor único.

## 2026-11-12 — implementación y gates locales

- Backend: `SaleTemporalEvidence` y timestamp por pago desde columnas V1;
  aliases SQL explícitos y JSON ISO mediante Java Time.
- Frontend: `RecordedInstant.fromWire` acepta sólo UTC ISO válido, rechaza
  calendarios imposibles y presenta `Unknown` como `No disponible`.
- `/ventas`, panel de operación y `/ticket/:saleId` muestran creación, última
  actualización y registración de cada pago.
- Typecheck PASS; tests frontend enfocados 32/32; suite completa 432/432; build
  productivo PASS.
- Controller backend enfocado PASS; `SaleAdmissionPostgresTest` PASS con
  reinicio/timestamps; suite backend completa PASS en 6m32s.

Resta congelar SHA, ejecutar dos reviews GPT-6.1 Sol y entregar por PR/CI.

## Remediación de review

La primera ronda exact-head rechazó un P2: exigir orden temporal podía bloquear
una venta ante retroceso del reloj. `SaleTemporalEvidence` ahora es cerrado
`Known|Unknown`; una combinación invertida degrada sólo la evidencia de fecha.
La regresión PostgreSQL fija y compara los tres instantes, recrea repositories,
invierte el reloj y continúa el flujo durable. También se afirma el JSON ISO.
Tests remediados controller+PostgreSQL: 23 PASS. Backend completo posterior:
335/335 PASS en 6m30s, sin fallos, errores ni omitidos.

## Reviews exact-head

GPT-6.1 Sol medium Bug/Producto y Seguridad/Arquitectura aprobaron de forma
independiente base `1f16522` y head `7c4f1d0`, sin P0–P3. Verificaron el tipo
cerrado `Known|Unknown`, preservación exacta, JSON ISO, traductor frontend único
y continuidad comercial ante reloj invertido. PR, CI y post-merge siguen como
gates; estas reviews no acreditan integraciones externas ni homologación.
