import { Component, provideZoneChangeDetection } from '@angular/core';
import { bootstrapApplication } from '@angular/platform-browser';
import { provideRouter, RouterOutlet } from '@angular/router';
import { demoRoutes } from './features/demo/presentation/demo.routes';
@Component({ selector: 'bs-root', standalone: true, imports: [RouterOutlet], template: '<router-outlet />' })
class DemoRootComponent {}
export const bootstrapDemo = () => bootstrapApplication(DemoRootComponent, {
  providers: [provideZoneChangeDetection({ eventCoalescing: true }), provideRouter([{ path: 'demo', children: demoRoutes }])],
});
