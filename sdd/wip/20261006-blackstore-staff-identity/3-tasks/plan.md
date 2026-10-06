# Plan de implementación

1. Aprobar contrato HTTP, matriz endpoint→permiso→ownership, amenazas y límites.
2. Implementar tipos cerrados y política pura exhaustiva.
3. Agregar migración, repositorios, Clock, token, BCrypt y rate limit.
4. Crear provisionamiento local y retirar de forma segura la credencial ficticia.
5. Implementar login, resolución, logout, expiración, revocación y auditoría.
6. Integrar cookie, CSRF, errores y configuración same-origin/loopback.
7. Proteger todos los controllers/casos de uso y corregir ownership/atribución.
8. Implementar `/sesion`, store tipado, guards, shell y logout.
9. Ejecutar integración PostgreSQL, browser real y regresión TP/caja/reportes.
10. Repetir CI y reviews de bugs, seguridad y SDD sobre HEAD exacto.

## Matriz mínima de pruebas

- Rol × acción × propio/ajeno, Unknown, cuenta inactiva y asociación ambigua.
- Password correcta/incorrecta/inexistente/hash inválido con respuesta uniforme.
- Sesión alterada, vencida, revocada, reinicio, cambio de rol y carrera touch/logout.
- CSRF ausente, incorrecto, cruzado, replay preauth, token anterior a login/logout, sesión preexistente y origen ajeno: cero efecto/outbox.
- Rate limit: umbrales, backoff, reset, reinicio y ataques distribuidos por login/origen.
- Todos los endpoints: `401` sin sesión, `403` sin permiso; headers falsos no elevan.
- Ownership caja→venta→pago y GET por operationId sin filtración.
- Atribución real en caja, reserva, reversa y egreso.
- Migración desde V3 con datos/FK intactos, digest único, fallo/restore documentado y seed idempotente sin reset.
- UI deep link, loading, recarga, logout, expiración, red y respuestas tardías.
- Regresión TP-001..006 y ausencia de tráfico live.
- Browser real: cookie + CSRF + proxy/origen, sin `route.fulfill` como evidencia principal.

## Gate

Implementación sólo después de review funcional/arquitectura y seguridad del SDD. Cierre local sólo con CI backend/frontend/PostgreSQL, browser real, inventario de rutas protegido y revisiones exactas sin pendientes.
