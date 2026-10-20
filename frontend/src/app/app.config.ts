import { ApplicationConfig, inject, provideAppInitializer, provideZoneChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { sessionInterceptor } from './core/infrastructure/session.interceptor';
import { SessionStore } from './core/services/session.store';
import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes),
    provideHttpClient(withInterceptors([sessionInterceptor])),
    provideAppInitializer(() => inject(SessionStore).bootstrap()),
  ],
};
