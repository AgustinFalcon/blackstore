# Despliegue aislado — BlackStore companion

Misma VM que StoreCore está permitida. El proceso, la base, el rol de base, la identidad de servicio, los secretos y la auditoría son distintos.

| Límite | BlackStore | StoreCore |
|---|---|---|
| Proceso | `blackstore-backend` puerto 8081 | proceso propio |
| Base | PostgreSQL de BlackStore, migración `V1__blackstore_schema.sql` | base propia, sin FK cruzada |
| Credencial | `service_client_ref` opaco | credencial de StoreCore, no se resuelve aquí |
| Contrato | se consume el YAML canónico de StoreCore, no se copia | dueño del OpenAPI |

El modo de integración por defecto es `fixture`. No hay cliente HTTP, DSN de StoreCore, secreto ni Flyway de integración. El adaptador real sigue bloqueado hasta evidencia StoreCore `TASK-PIC-001..008` y un GO de Sol específico para `20260921-storecore-connector-adapter`.

El contrato en este repositorio es un puntero. No implica un cambio en StoreCore.
