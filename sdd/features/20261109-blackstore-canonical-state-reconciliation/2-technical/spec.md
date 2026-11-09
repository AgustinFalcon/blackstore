# Spec técnico — reconciliación canónica

## Fuente de verdad

- Merge `40c28801a8d619ef4d1a8eaca1f424e36bb074a9` de PR #61.
- Verify exact-head #109, run `42581477663`, cuatro jobs verdes.
- SDD archivado `features/20261109-blackstore-productive-surface-data-parity`.

## Reglas

- Actualización sólo documental.
- La reconciliación usa una taxonomía documental controlada: `resolved`, `integrado`, `blocked` o `deferred`, con un calificador de alcance cuando el cierre sea parcial (por ejemplo, sólo datasets BlackStore-owned). Una línea histórica no puede volver a abrir un gate resuelto.
- La aceptación DCT se limita a datasets BlackStore-owned. El adapter StoreCore live continúa separado y fail-closed.
- Enlaces relativos deben resolver dentro de `sdd/`.
