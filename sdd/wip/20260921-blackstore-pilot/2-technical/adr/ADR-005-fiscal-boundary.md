# ADR-005 — Fiscal status never suppresses sales

**Status:** proposed

Fiscal adapter/emission es repositorio externo. Toda venta comercial se registra; no se permite ocultar, omitir, borrar, doble libro ni alterar monto. `NOT_CONFIGURED` sólo corresponde a test/dev. En producción, una venta real necesita mecanismo fiscal externo válido o excepción legal documentada y vigente, con aprobación/firmas de responsable y contador. Una trigger/guard de producción bloquea COMMITTED sin esa autorización. Agregación mensual es deny-by-default hasta regla legal y contador aprobados.
