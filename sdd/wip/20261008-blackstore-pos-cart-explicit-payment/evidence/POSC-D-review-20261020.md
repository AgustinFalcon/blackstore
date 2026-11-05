# POSC-D — revisión coordinada de diseño

- Fecha: 2026-10-20.
- Snapshot local inspeccionado: respaldo completo `115fad53006b55d27b021883bf49153a3de050fa`. La revisión se limitó a la documentación POSC contenida allí; la implementación no separada del mismo snapshot no se atribuye a este PR.
- Alcance: documentación POSC; implementación y browser no ejecutados.
- Revisores: GPT-6.1 Astra técnica, seguridad y producto/UX, coordinados entre sí.

## Primera ronda

NO-GO con seis P2 y una precisión P3: presupuesto/shape del journal V2; U+0000; sesión revocada durante espera; lock de terminal; recuperación de venta Reserved/parcial con journal resuelto; salida después de rechazo terminal por línea; semántica release Net/Legacy/Unknown.

## Resolución

- AdmissionV2 tiene allowlist anidada y presupuesto de 524288 bytes UTF-8; V1 conserva 32768 caracteres y bytes históricos.
- Todos los textos V2 rechazan U+0000 y UTF-16 inválido antes de hash/journal/persistencia.
- La ruta v3 transporta contexto interno de sesión; revalida bajo locks y define la revalidación exitosa como punto de admisión.
- Orden normativo: lifecycle→comando/operación→caja→terminal→venta→delivery; terminal retenida hasta commit.
- La venta durable se retoma por `operationId` aunque el journal esté resuelto, con autoridad actual y lectura backend.
- Un rechazo terminal resuelto permite corregir borrador con nuevos commandId/operationId y conserva saleId/clientInstanceId/deviceId; timeout/404 no.
- Release conserva política CLR: Net cero correlacionado puede liberar; Legacy no amplía permiso; Unknown bloquea.
- A01/A02/A03/A05/A07/A09 y la matriz UX incorporan las pruebas y salidas correspondientes.

## Gate

Técnica APPROVE final, seguridad APPROVE y producto/UX APPROVE; sin P0–P3 pendientes. POSC-D `done`. Esto habilita comenzar POSC-B, pero no acredita Implementation, Integration, homologación ni publicación.
