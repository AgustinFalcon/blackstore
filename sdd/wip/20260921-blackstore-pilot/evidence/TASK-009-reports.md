# TASK-009 — Shift reports

**Completed:** 2026-09-22

`ShiftFigures` keeps gross sales, discounts, net sales, refunds, collected, fees and expenses distinct. Margin is null without a validated cost. `ReportFormulas.contribution` is not a fiscal result and not free cash.

Gate: `AutonomousCoreTest.reportsKeepMarginUnknownAndProjectionsNonFiscal`.
