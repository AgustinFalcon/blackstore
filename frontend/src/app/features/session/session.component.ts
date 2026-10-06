import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { SessionState } from '../../core/domain/session-types';
import { SessionStore } from '../../core/services/session.store';

@Component({ selector: 'bs-session', standalone: true, imports: [FormsModule], template: `
  <section class="page"><h2>Sesión de staff</h2><p role="status">{{ session.state().label }}</p>
  @if (session.state().isAuthenticated) {
    <button (click)="continue()">Continuar</button><button (click)="session.logout()">Cerrar sesión</button>
  } @else if (session.state() !== loading) {
    <form (ngSubmit)="submit()">
      <label>Usuario <input name="login" autocomplete="username" [(ngModel)]="login" required /></label>
      <label>Contraseña <input name="password" type="password" autocomplete="current-password" [(ngModel)]="password" required /></label>
      <button type="submit" [disabled]="!login.trim() || !password">Ingresar</button>
    </form>
    <button type="button" class="ghost" (click)="session.bootstrap(true)">Comprobar sesión</button>
  }
  </section>` })
export class SessionComponent {
  readonly session = inject(SessionStore);
  private readonly router = inject(Router);
  readonly loading = SessionState.Loading;
  login = ''; password = '';
  constructor() { void this.session.bootstrap(); }
  async submit(): Promise<void> {
    if (!this.login.trim() || !this.password || this.session.state() === SessionState.Loading) return;
    const password = this.password; this.password = '';
    if (await this.session.login(this.login.trim(), password)) await this.continue();
  }
  async continue(): Promise<void> { await this.router.navigateByUrl(this.session.landing()); }
}
