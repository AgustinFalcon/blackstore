# Frontend test runner — karma-jasmine

**Fecha:** 2026-09-23  
**Límite:** tooling only. Sin rutas nuevas. Sin conector browser.

Sol `20260923-sol-post-l3-go.md`: GO para dependencias mínimas Angular 22 + `npm test`.

Añadidos (dev only): `karma`, `karma-chrome-launcher`, `karma-jasmine`, `karma-jasmine-html-reporter`, `karma-coverage`, `jasmine-core@~5.1.0`, `@types/jasmine@~5.1.0`. `jasmine-core@7` rompe `zone.js` (`describe` read-only).

`npm test` (ChromeHeadless): **2 SUCCESS** (`AppComponent` banner bloqueado / backend down). No se reclama pixel-complete ni suite de producto completa.
