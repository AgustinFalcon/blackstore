# Especificación funcional

## Problema

BlackStore obtiene catálogo desde un adapter, pero una lectura loopback se
pierde al reiniciar y una caída deja la pantalla sin la última evidencia válida.
El operador necesita continuidad de lectura sin confundir cache, fixture y
StoreCore conectado.

## Reglas

1. Sólo un snapshot `STORECORE_LIVE`, contractual y estructuralmente válido se
   puede guardar.
2. El snapshot guardado se devuelve como `STORECORE_CACHE`, nunca como live.
3. Una respuesta remota válida con vencimiento explícito reemplaza atómicamente
   el snapshot anterior; un snapshot sin `validUntil` no es cacheable.
4. Una ausencia/falla remota puede usar el último cache; un cache vencido o sin
   ítems es visible pero no seleccionable ni vendible.
5. Valores de procedencia desconocidos entran como `UNKNOWN` y fallan cerrado.
6. Fixture y blocked no escriben ni leen este cache.
7. Reinicio del backend conserva versión, contrato, timestamps, ítems y versiones
   de precio sin fabricar stock, precio o procedencia.

## Criterios de aceptación

- CA-01: primera lectura válida persiste un snapshot completo y retorna live.
- CA-02: tras reinicio y caída remota se recupera el mismo snapshot como cache.
- CA-03: un snapshot corrupto, vacío, vencido o de contrato incompatible no se
  guarda como nueva autoridad.
- CA-04: la UI etiqueta cache y conserva `Unknown` fail-closed.
- CA-05: configuración default sigue fixture y no crea tráfico StoreCore.
- CA-06: PostgreSQL limpio y upgrade aplican la migración; tests de dominio,
  persistencia y frontend cubren traductores y reglas.

## Fuera de alcance

Activación live, sincronización en background, credenciales productivas,
reservas contra producción, fiscal/ARCA, Correo Argentino, deploy y homologación.
