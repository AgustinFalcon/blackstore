# ADR-006 — Versioned report definitions

**Status:** proposed

`gross_sales` is pre-discount amount. `discounts` is granted discount. `net_sales = gross_sales - discounts`; `refunds` is separate and must not be subtracted twice. `collected` is captured payments and is distinct from sales. `fees_paid` and `expenses_paid` are paid ledger sums. `operating_cash_flow = collected - refunds - fees_paid - expenses_paid`.

Gross margin exists only with validated cost/source/version/effective_at. Contribution, reinvestment suggestion and owner surplus estimate require formula version, period, declared inputs and data-completeness state; all are non-fiscal/non-free-cash projections.
