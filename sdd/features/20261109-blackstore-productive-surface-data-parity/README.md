# Paridad productiva de superficies BlackStore

Este feature cierra la cobertura observable de datos del POS operativo sin convertir la demo en backend, sin duplicar autoridad de StoreCore y sin activar integraciones externas.

## Resultado

- Diez familias de superficie y nueve rutas operativas gobernadas por un manifest ejecutable.
- Procedencia y persistencia expresadas con tipos cerrados y caso `Unknown` fail-closed.
- Catálogo traducido en un mapper único; referencias opacas V2 preservadas byte a byte.
- Ventas bloqueadas cuando la persistencia durable no está comprobada.
- Evidencia visible y automatizable por superficie y dataset.
- StoreCore live, fiscal, pagos, envíos y `release/1.0` permanecen fuera de alcance.

## Validación

- Frontend unitario: 414/414.
- Demo browser: 52/52; ODR: 4/4; DCT real: 18/18.
- Backend completo, `bootJar`, build Angular, typechecks y manifest: PASS.
- PR #61, Verify #107: frontend, frontend-browser, backend y dct-browser verdes.
- Reviews GPT-6.1 Sol: Bug, Security y UX/Producto APPROVE; cero P0–P3.

Los specs funcional y técnico, el manifest, las tareas y el historial de implementación permanecen dentro de este archivo de feature.
