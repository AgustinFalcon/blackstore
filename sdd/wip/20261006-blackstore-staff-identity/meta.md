# Identidad y autorización de staff BlackStore

- Feature: `blackstore-staff-identity`
- Fecha: 2026-10-06
- Base exacta: `b9211764f525d723d020c780d4eb62564ffaadf6` (`master`, PR #23).
- Estado: `approved_for_implementation`; reviews funcional/arquitectura y seguridad aprobadas sobre `804826cbaaa75b476ce9ae2803da2a156656b211`.
- Destino: `master`, únicamente identidad local fail-closed.
- Alcance: login propio de staff, sesiones opacas persistidas, cookie segura, CSRF, RBAC/ownership, `/sesion`, guards, logout y atribución confiable.
- Fuera de alcance: federación StoreCore, adapter live, fiscal, Mercado Pago, Correo Argentino, recovery comercial durable, deploy y `/sdd.finish`.

Este corte elimina la autoridad de `X-Actor-Id` y `X-Role`. No convierte a BlackStore en sistema homologado: sólo establece una frontera de identidad local necesaria para los siguientes cortes.
