# BlackStore durable catalog cache

Estado: `merged_local_complete_external_activation_blocked`.

Base: `10a7862e7a38d8b8bb4b305ae0a847afee4f57cc`; PR #66 integrado en
`master 1f16522e78e7f7f04374ae1b58bcb2b92fa24e94`.

Objetivo: reemplazar el último catálogo operativo puramente efímero por una
proyección durable de snapshots válidos recibidos desde StoreCore, sin activar
StoreCore live, tráfico externo, fiscal, proveedores ni `release/1.0`.

El modo predeterminado continúa `fixture`. El cache sólo participa en el modo
opt-in `loopback`, conserva procedencia cerrada y falla cerrado.
