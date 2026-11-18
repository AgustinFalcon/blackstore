# Catálogo fixture de demostración

- Estado: `integrated` por PR #22 (`a92ec07868638e312e568f3dc36837ea78cdde44`)
- Tipo: datos fixture locales, sin integración operativa
- Base: `6b3be5be543cd316918d0f6495d8f5e2b433ef85` (master después de mergear PR #19)
- Destino: PR #22 sobre `master`; reemplaza el PR #21 cerrado al eliminarse su base apilada; no se mezcla con `release/1.0`

Este corte amplía exclusivamente la proyección fixture de solo lectura para probar la UI. No crea tablas, endpoints de escritura, credenciales, llamadas a StoreCore ni autorización live.

Reconciliación 2026-11-16: el estado `in_progress` era histórico. El fixture se
integró en PR #22 y continúa identificado como simulación local; no acredita
StoreCore live ni homologación.
