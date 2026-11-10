# Selección operativa de ventas BlackStore

Este feature permite abrir de forma explícita y durable las operaciones de cualquier venta visible desde `/ventas`, sin depender de estado incidental de otra ruta y sin agregar endpoints ni escritores.

## Resultado

- Cada venta ofrece “Ver operaciones” y la URL `operationId` es la única intención durable.
- Los estados None, Loading, Ready, Unavailable y Unknown son tipos cerrados y fail-closed.
- Recarga, historial, retry, cierre y cambio de sesión invalidan respuestas tardías.
- Todos los publicadores de detalle comparten una generación de publicación; ningún `open` o recibo anterior puede dirigir comandos contra otra identidad.
- El panel conserva autoridad HTTP/PostgreSQL, mantiene separados GET y comandos journaled, valida importes y limpia borradores por identidad.
- Foco, anuncios, teclado, targets de 44 px y reflow 320/390/1440 están cubiertos.

## Validación

- Angular: 428/428.
- Demo comercial: 52/52.
- ODR: 10/10.
- DCT real: 18/18 con PostgreSQL 16, JAR real y Angular; recursos descartados tras cada escenario.
- Backend, `bootJar`, build, typechecks y manifest: PASS.
- Reviews GPT-6.1 Sol de UX/Producto, Código/Arquitectura y Seguridad: APPROVE, cero P0–P3.
- PR #63: Verify #42725213277 con `frontend`, `frontend-browser`, `backend` y `dct-browser` verdes; squash merge `76fd688` a `master`.

El alcance sigue siendo frontend productivo sobre APIs existentes. StoreCore live, fiscal, QR, proveedores y despliegue permanecen fuera de este corte.
