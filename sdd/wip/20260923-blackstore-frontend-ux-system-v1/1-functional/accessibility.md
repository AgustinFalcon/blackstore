# Accesibilidad y teclado

- WCAG 2.2 AA. Light only.
- Focus visible: outline 2px `#1d4ed8` + offset 2px.
- Targets táctiles ≥44px (qty, keypad, primary).
- Atajos documentados en UI: F2 abrir/caja, F4 nuevo ticket. No capturar si el foco está en un campo de texto multilinea.
- Banner StoreCore/entitlement: `role="status"` si informativo, `role="alert"` si bloquea write.
- Tablas: `<th>` + caption. Money `tabular-nums` y `aria-label` “Efectivo {monto}”.
- Dialogos: focus trap, Esc = cancel, return focus al opener.
- Contraste header navy `#0f172a` / `#f8fafc`.
- Reduced motion: sin carruseles en POS (no hay banner rotativo).
