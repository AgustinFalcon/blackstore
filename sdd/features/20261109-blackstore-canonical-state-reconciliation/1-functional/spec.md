# Spec funcional — reconciliación canónica

## Resultado esperado

El lector debe distinguir con precisión:

1. capacidades BlackStore-owned ya integradas y aceptadas;
2. historia supersedida que no constituye trabajo pendiente;
3. integraciones StoreCore/proveedor que continúan bloqueadas.

## Criterios de aceptación

- `STATUS`, `BACKLOG` y `RELEASE` identifican `master 40c2880`, PR #61 y Verify #109.
- POSC, caja/reportes y DCT BlackStore-owned no aparecen como pendientes activos.
- StoreCore live, contrato companion, facturación, pagos, envíos y homologación permanecen explícitamente NO-GO.
- No se modifica código ejecutable ni se afirma una homologación inexistente.
