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

## Ronda 3 — `ad6236c90fb523e2304466d1505949723e819f5f` — APPROVE

Bug/Producto y Seguridad/Arquitectura verificaron que desde `9374ba9` sólo se
agregó evidencia SDD. Ambas aprobaron sin P0–P3 y sin modificar archivos.

## Ronda 4 — PR #66 + corrección DCT — NO APPROVE

Modelo: GPT-6.1 Sol, esfuerzo medium. Revisiones independientes.

- P2 Seguridad: el loopback colapsaba rechazo/JSON inválido/caída en `null`,
  por lo que una autoridad remota inválida podía habilitar fallback a cache.
- P2 Bug/Producto: V14 limitaba nombres a 240 aunque el contrato admite 250;
  una escritura rechazada podía dejar cache vacío o anterior.

Se remediaron con un resultado cerrado en el borde de transporte, fallback sólo
para `Unavailable`, pruebas HTTP 401/403/malformed/envelope inválido y columnas
de 250 con prueba PostgreSQL de frontera. Estas revisiones quedan rechazadas;
se requiere una ronda nueva sobre el commit exacto.

## Ronda 5 — `33176f84542a391ef1276dd731092b2be7f41a5b` — NO APPROVE

- Seguridad/Arquitectura: `APPROVE`, sin P0–P3.
- Bug/Producto: P2, `raw_name VARCHAR(250)` no preservaba un nombre raw con
  padding cuya forma normalizada válida mide 250.

Se cambió únicamente la evidencia raw a `TEXT` y se amplió el test PostgreSQL
para verificar recuperación exacta del valor padded. La aprobación de Seguridad
no se hereda al nuevo commit; ambas revisiones deben repetirse.

## Ronda 6 — `5631260e3639fb4bcd989b6f42fc2769a14a3f86` — APPROVE

Modelo: GPT-6.1 Sol, esfuerzo medium.

- Bug/Producto: `APPROVE`, sin P0–P3. Verificó todas las remediaciones, los 15
  tests enfocados y la expectativa DCT V14.
- Seguridad/Arquitectura: `APPROVE`, sin P0–P3. Verificó fallback sólo ante
  `Unavailable`, rechazo de evidencia inválida, SQL parametrizado, atomicidad y
  recuperación exacta del nombre raw padded.

Ambas revisiones fueron read-only. El siguiente commit sólo incorpora este
registro; requiere una verificación documental de identidad antes del merge.
