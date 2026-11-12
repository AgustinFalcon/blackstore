# Revisiones

## Ronda 1 — `dcaf3c9` — NO APPROVE

Modelo: GPT-6.1 Sol, esfuerzo medium. Revisiones independientes Bug/Producto y
Seguridad/Arquitectura.

Hallazgos remediados:

- P1/P2: `latest()` podía mezclar cabecera y proyecciones bajo `READ_COMMITTED`.
  Se fijó `REPEATABLE_READ` y se agregó carrera PostgreSQL de reemplazo/lectura.
- P1: `NUMERIC(19,2)` podía redondear un precio no representable. V14 usa
  `NUMERIC(14,2)` y el validador aplica `MoneyPolicy.valid` antes de persistir.
- P2: `LEGACY`/`V2` eran strings internos. Ahora son `CatalogProjectionKind`.
- P2: una respuesta remota incompatible podía caer al cache. Toda respuesta no
  nula inválida/incompatible falla cerrada; sólo ausencia real usa fallback.
- P2: un cache corrupto sin vencimiento podía rehabilitarse. V14 exige
  `valid_until NOT NULL` y el decorador igualmente lo considera stale.

Se requiere nueva ronda sobre el SHA corregido; estas revisiones no se heredan.

## Ronda 2 — `9374ba93653637aa8c364e58196161b4997be1fc` — APPROVE

Modelo: GPT-6.1 Sol, esfuerzo medium.

- Bug/Producto: `APPROVE`, sin P0–P3. Verificó las cuatro correcciones de su
  ronda anterior, persistencia LEGACY/V2, clean/upgrade, concurrencia y SDD.
- Seguridad/Arquitectura: `APPROVE`, sin P0–P3. Verificó `REPEATABLE_READ`,
  atomicidad, precisión monetaria, contrato, vencimiento, permisos, raw y
  activación opt-in apagada por defecto.

Ambas revisiones fueron estáticas y no modificaron archivos. El commit
documental que incorpora este registro requiere verificación de identidad antes
del PR; no hereda aprobación funcional por sí solo.
