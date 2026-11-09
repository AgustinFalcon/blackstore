# Paridad de datos de superficies productivas BlackStore

- **Feature:** `blackstore-productive-surface-data-parity`
- **Base:** `master` `2b1f60c44ba50c915293f48e2a83554f9ca81943`
- **Estado:** `complete`
- **Cierre:** PR #61; commit funcional validado por Verify #107 y reviews Bug/Security/UX-Producto APPROVE. El archivo SDD queda sujeto a los required checks del head final.
- **Objetivo:** distinguir y cerrar la cobertura de datos del POS operativo sin presentar la demo como backend productivo ni duplicar autoridad de StoreCore.
- **Superficies:** `/sesion`, `/`, `/caja`, `/caja/cierre`, `/catalogo`, `/ticket`, `/ticket/:saleId`, `/ventas`, `/reportes`.
- **Demo:** `/demo/**` permanece simulación reiniciable en memoria, claramente rotulada.
- **Fuera de alcance:** storefront comprador, catálogo local autoritativo, StoreCore live, Mercado Libre, QR/proveedor real, fiscal, envíos, homologación, despliegue y cambios en `release/1.0`.

## Regla de autoridad

BlackStore es dueño de identidad staff, caja, ticket, pagos presenciales registrados, gastos, arqueos y reportes. StoreCore es dueño de catálogo, precios, costos y stock. Un dato bloqueado o no verificable se representa como tal; nunca se inventa desde la demo, se transforma en cero ni se copia a otra autoridad.
