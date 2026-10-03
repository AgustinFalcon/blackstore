# Especificación funcional

## Objetivo

Permitir una prueba local representativa de `/catalogo` y `/ticket` con cinco artículos argentinos, conservando el catálogo como autoridad externa de solo lectura.

## Criterios

- El fixture expone cinco SKU únicos con nombre, variante, versión de precio y precio unitario.
- `SKU-1` permanece disponible para no romper los flujos existentes.
- El catálogo conserva versión, vigencia y política de bloqueo por `stale`.
- No existe alta de artículos desde BlackStore ni conexión StoreCore en este corte.
