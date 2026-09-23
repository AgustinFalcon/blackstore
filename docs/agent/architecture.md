# Architecture guidance

BlackStore es un bounded context propio. Si comparte VM con StoreCore, conserva contenedor/proceso, DB, DB user, secrets y audit distintos. Toda interacción va por cliente API versionado con identidad BlackStore asociada a una única instalación StoreCore.
