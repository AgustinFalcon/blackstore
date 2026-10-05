# Política local de transiciones de ticket y pago

- Feature: `blackstore-ticket-payment-transition-policy`
- Fecha: 2026-10-05
- Estado: `draft`; implementación pendiente de revisión Sol y GO explícito del corte.
- Base exacta: `a92ec07868638e312e568f3dc36837ea78cdde44` (`master`, PR #22).
- Rama prevista: `feature/ticket-payment-transition-policy`; destino `master` para homologación local.
- Fuente de diseño: plan Astra de política local fail-closed, regla global de tipos cerrados/objetos, `AGENTS.md`, `sdd/PROJECT.md`, `sdd/PATTERNS.md` y precedencia de `sdd/STATUS.md`.
- Alcance: elegibilidad local para reservar, capturar pago, confirmar y liberar por `operationId`; flujo UI compuesto por objetos de paso.
- Trazabilidad: TP-001..TP-006 en especificaciones, plan y tasks.

Este WIP documenta una garantía propuesta; no acredita implementación ni pruebas verdes. Dos reviews históricos devolvieron `CHANGES_REQUESTED`; el draft corregido incorpora sus requisitos y queda pendiente de nueva revisión, sin GO. Los estados wire ya cerrados del frontend no garantizan por sí mismos las escrituras. La documentación canónica tiene stamps históricos: la base Git de este corte es la indicada aquí, sin reinterpretar esos stamps como evidencia nueva.

Se preservan integración deshabilitada, adapter fixture por defecto, kill switch y canary 0. No concede live, credenciales, companion, fiscal, Mercado Pago, Correo Argentino, rollout, deploy ni `/sdd.finish`. Recovery durable, atomicidad global y reportes requieren cortes separados.
