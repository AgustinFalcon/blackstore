import { Component, provideZoneChangeDetection } from '@angular/core';
import { bootstrapApplication } from '@angular/platform-browser';
@Component({ selector: 'bs-root', standalone: true, template: '<main><h1>Pantalla no disponible</h1><p>Elegí una superficie para continuar.</p><a href="/demo">Demostración</a> · <a href="/sesion">Acceder al POS</a></main>' })
class UnknownRootComponent {}
export const bootstrapUnknown = () => bootstrapApplication(UnknownRootComponent, { providers: [provideZoneChangeDetection({ eventCoalescing: true })] });
