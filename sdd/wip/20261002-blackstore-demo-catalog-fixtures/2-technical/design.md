# Diseño técnico

`FixtureCatalogAdapter` sigue siendo el único traductor del fixture al tipo cerrado de catálogo. Se agregan filas inmutables con `BigDecimal`; controllers y UI continúan consumiendo `CatalogSnapshot` sin strings de estado nuevos.

La regresión HTTP comprueba cantidad y campos representativos. El modo por defecto continúa `fixture`, la integración continúa `enabled: false` y los kill switches permanecen cerrados.
