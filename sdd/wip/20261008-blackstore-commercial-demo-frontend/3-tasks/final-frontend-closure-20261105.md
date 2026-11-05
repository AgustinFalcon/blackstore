# Plan de cierre frontend — 2026-11-05

Estado inicial: APPROVED_FOR_IMPLEMENTATION por instrucción explícita del usuario y
revisión coordinada Astra UI/UX/Product + Angular/Security.

- [x] **FFC-01 Integridad** — turnos/cierres históricos, overflow agregado y reportes
  por snapshots estables, con tests failing-before y regresión.
- [x] **FFC-02 Contrato de presentación** — tipos cerrados en la vista, precondiciones
  de pago y validación accesible por campo.
- [x] **FFC-03 Experiencia comercial** — dashboard diario, comprobante unificado,
  resumen de caja histórico/imprimible, contraste y responsive Demo/Real.
- [x] **FFC-04 Evidencia** — typecheck, build, unit, dominio, browser completo,
  inventario CTA, viewport/zoom/a11y y reviews independientes.
- [x] **FFC-05 Cierre SDD** — evidencia exact-head y límites live/POSC preservados.

Orden: FFC-01 y la parte visual no dependiente de FFC-02 pueden avanzar en paralelo;
FFC-04 sólo corre tras integrar ambos cortes. No hay deploy, push, merge ni
homologación en este plan.

