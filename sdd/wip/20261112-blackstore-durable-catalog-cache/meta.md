# BlackStore durable catalog cache

Estado: `implemented_local_gates_and_reviews_passed_pr_pending`.

Base: `10a7862e7a38d8b8bb4b305ae0a847afee4f57cc` (`origin/master`).

Objetivo: reemplazar el último catálogo operativo puramente efímero por una
proyección durable de snapshots válidos recibidos desde StoreCore, sin activar
StoreCore live, tráfico externo, fiscal, proveedores ni `release/1.0`.

El modo predeterminado continúa `fixture`. El cache sólo participa en el modo
opt-in `loopback`, conserva procedencia cerrada y falla cerrado.
