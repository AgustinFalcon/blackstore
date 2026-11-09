# Plan — paridad productiva BlackStore

1. Congelar autoridad y procedencia de las diez familias de superficie.
2. Reconciliar STATUS/BACKLOG/RELEASE con PR #53–#60 ya integrados.
3. Implementar manifest y gate de cobertura de rutas.
4. Implementar tipos cerrados de procedencia/readiness y mapper de catálogo fail-closed.
5. Corregir copy y estados de inicio/catálogo sin activar StoreCore live.
6. Profesionalizar ventas y comprobante usando sólo contratos existentes; campos ausentes quedan `No disponible`.
7. Profesionalizar caja/reportes usando sólo contratos existentes, sin rutas, DTOs ni permisos nuevos.
8. Ejecutar gates unitarios, arquitectura, responsive/axe y browser real PostgreSQL 16, incluido el journey con reinicio y la matriz negativa.
9. Ejecutar reviews código/producto y seguridad; corregir hasta cero P0–P3.
10. Crear PR, CI y merge a `master`; no tocar `release/1.0`.
