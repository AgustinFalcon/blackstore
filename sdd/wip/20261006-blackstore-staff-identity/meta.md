# Identidad y autorización de staff BlackStore

- Feature: `blackstore-staff-identity`
- Fecha: 2026-10-06
- Base exacta: `b9211764f525d723d020c780d4eb62564ffaadf6` (`master`, PR #23).
- Estado: `integrated` por PR #24 (`4055b281d15ad1d7aee6a9624349c41ca15a3638`).
- Destino: `master`, únicamente identidad local fail-closed.
- Alcance: login propio de staff, sesiones opacas persistidas, cookie segura, CSRF, RBAC/ownership, `/sesion`, guards, logout y atribución confiable.
- Fuera de alcance: federación StoreCore, adapter live, fiscal, Mercado Pago, Correo Argentino, recovery comercial durable, deploy y `/sdd.finish`.

Este corte elimina la autoridad de `X-Actor-Id` y `X-Role`. No convierte a BlackStore en sistema homologado: sólo establece una frontera de identidad local necesaria para los siguientes cortes.

Reconciliación 2026-11-16: la espera de merge era histórica. La integración
local no federa StoreCore ni acredita deploy u homologación.
