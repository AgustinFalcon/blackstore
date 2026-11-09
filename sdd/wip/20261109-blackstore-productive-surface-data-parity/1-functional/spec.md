# Spec funcional — paridad productiva BlackStore

## Problema

BlackStore ofrece una demo comercial completa y un POS operativo autenticado. Hoy ambas superficies pueden parecer equivalentes aunque no comparten procedencia: la demo usa `LocalDemoRepository`; el POS consume HTTP, pero su catálogo por defecto continúa siendo fixture. Se necesita una verdad verificable pantalla por pantalla antes de afirmar que el backend alimenta toda la experiencia.

## Personas

- **Cajero:** abre caja, arma una venta, cobra y recupera una operación.
- **Supervisor/owner:** consulta ventas, caja, arqueos y reportes.
- **Auditor:** consulta reportes sin recibir permisos de escritura.
- **Demostrador:** recorre `/demo/**` sabiendo que todos los datos son simulados.

## Resultados exigidos

1. Cada dataset productivo declara procedencia `REAL`, `FIXTURE`, `MIXED`, `UNAVAILABLE`, `BLOCKED` o `UNKNOWN` mediante un tipo cerrado traducido una sola vez en el borde. La superficie agrega esas procedencias sin ocultarlas.
2. La UI no presenta `fixture-v1`, IDs técnicos o evidencia incompleta como información comercial confirmada.
3. Sesión, caja, ventas, comprobante y reportes leen datos durables de BlackStore cuando el perfil PostgreSQL está activo; error, vacío y no disponible son estados distintos.
4. Catálogo, precio y stock no se promueven a autoridad BlackStore. Mientras StoreCore live siga bloqueado, la UI identifica el catálogo como simulador local y no promete disponibilidad.
5. `/demo/**` conserva sus mocks, escenarios y amplitud comercial, con una indicación inequívoca de simulación sin proveedor/fiscal/stock real.
6. Las vistas operativas priorizan lenguaje comercial y dejan IDs, versiones y trazas dentro de evidencia técnica secundaria.
7. Ningún cambio habilita StoreCore live, fiscal, QR real ni proveedores externos.

## Historias y aceptación

### F01 — verdad de procedencia

Como operador, quiero saber si los datos son reales, simulados o no disponibles para no tomar decisiones sobre una fuente equivocada.

- Toda respuesta wire se traduce a tipos cerrados con `Unknown`.
- Un valor desconocido falla cerrado y nunca imprime el literal crudo.
- El manifest esperado y la evidencia observada son artefactos separados: una expectativa nunca prueba el runtime.
- La procedencia se calcula sobre todos los datasets listados, que son requeridos salvo marca explícita futura. La precedencia determinista es: si existe `Unknown` => `Unknown`; si no, `Unavailable` => `Unavailable`; si no, `Blocked` => `Blocked`; todos `Real` => `Real`; todos `Fixture` => `Fixture`; cualquier combinación restante de `Real`, `Fixture` y `Mixed` => `Mixed`. Un fixture durable continúa siendo `Fixture`.
- Las superficies publican atributos de aceptación automatizable con identidad, procedencia agregada y procedencia por dataset.

### F02 — inicio operativo

Como operador, quiero un resumen útil de caja y actividad basado sólo en APIs existentes/autorizadas.

- Muestra sesión/caja actual y accesos permitidos. El inicio se declara `Mixed` mientras su resumen incluya catálogo fixture.
- Las métricas no soportadas se omiten o marcan no disponibles; no se derivan desde la demo.
- La persistencia no durable impide declarar el entorno listo para producción.

### F03 — catálogo operativo

Como cajero, quiero buscar productos sin que la pantalla atribuya al cajero una versión o disponibilidad inexistentes.

- Nombre, SKU, precio y versión pasan por un traductor runtime.
- Procedencia y frescura son dimensiones cerradas independientes; frescura admite `Current`, `Stale`, `Unavailable` y `Unknown`.
- No se muestra stock disponible si el contrato no lo entrega.
- `Stale`, `Unavailable` y `Unknown` bloquean selección/reserva. El fixture actual sólo permite aceptación local explícita y nunca prueba readiness productivo.
- El fixture queda rotulado; StoreCore live permanece deshabilitado.

### F04 — venta y comprobante

Como cajero, quiero reconstruir venta, pagos y estado desde el backend durable y reintentar lecturas sin repetir comandos.

- Los comandos sólo se habilitan desde transiciones cerradas y permisos efectivos.
- La venta en curso que observa catálogo fixture es `Mixed`. El detalle/listado histórico cuya procedencia de producto no está persistida se declara `Unknown`, aunque el dataset transaccional BlackStore sea `Real`; consultar el catálogo actual no acredita el origen histórico.
- El comprobante prioriza fecha, total en ARS, medios/estado y líneas; la identidad técnica queda secundaria.
- La pérdida de respuesta recupera por lectura autoritativa según contratos existentes.

### F05 — caja y reportes

Como supervisor, quiero consultar movimientos, cierre y reportes sin confundir evidencia técnica con totales comerciales.

- Lecturas usan PostgreSQL autoritativo y `no-store` donde corresponda.
- Los datasets contables del ledger son `Real`. Si un reporte incluye evidencia histórica de producto sin procedencia persistida, esa procedencia es `Unknown` y la superficie agregada también; nunca se copia la expectativa del manifest como observación.
- Vacío, error y truncado son explícitos.
- Zona/fecha/importe son validados; no se infiere autorización desde inputs del dispositivo.
- Exportaciones o históricos sólo entran si existe contrato backend y prueba real.

### F06 — demo honesta

Como demostrador, quiero exhibir todas las pantallas sin afirmar integraciones que todavía no existen.

- Demo conserva productos, clientes, inventario, pagos, QR, caja y reportes simulados.
- Cada flujo crítico indica que no acredita pago, fiscal, stock ni proveedor real.
- Nunca reutiliza cookies, SID ni datos del POS operativo.

## No objetivos

- Crear comprador/storefront en BlackStore.
- Agregar CRUD autoritativo de catálogo, inventario o clientes.
- Activar el adaptador StoreCore o modificar `release/1.0`.
- Homologar Mercado Pago/MODO/ARCA/Correo Argentino.
