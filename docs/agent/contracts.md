# Contracts

StoreCore reserva/confirma/libera inventario y expone catálogo versionado por API. BlackStore conserva ticket, cobros, caja/gastos y audit. Cuando StoreCore no responde, ventas nuevas se bloquean; catálogo local sólo se lee según freshness.

PCI/PII: nunca persistir PAN, CVV, track data ni secretos de adquirente; usar token/reference, brand/last4 opcionales y payloads/logs redactados. Toda PII tiene propósito, retención versionada y auditoría.
