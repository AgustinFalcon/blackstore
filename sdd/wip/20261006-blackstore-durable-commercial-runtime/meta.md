# Runtime comercial durable BlackStore

- Feature: `blackstore-durable-commercial-runtime`
- Fecha: 2026-10-06
- Base exacta: `4055b281d15ad1d7aee6a9624349c41ca15a3638` (`master`, merge PR #24).
- Estado: `ready_for_merge`; 9/9 tareas completas, aceptación browser/PostgreSQL/reinicio documentada, CI alojado verde sobre `f05344f7ff4d681adc1adb8966211bcc648e70a7` y reviews finales de bugs, seguridad y SDD aprobadas.
- Destino: `master`.
- Alcance: PostgreSQL como autoridad de ventas locales, comandos replayables, recovery por pasos, consulta/reapertura y prueba de reinicio.
- Fuera de alcance: StoreCore live, fiscal, Mercado Pago, Correo Argentino, multiinstancia, nuevas fórmulas contables, deploy y `/sdd.finish`.

Este corte no habilita integraciones externas. Cierra la brecha entre hechos persistidos y un runtime todavía dependiente de memoria.
