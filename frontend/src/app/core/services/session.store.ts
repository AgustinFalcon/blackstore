import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Subject, firstValueFrom } from 'rxjs';
import { API_BASE } from '../api';
import { SessionState, StaffIdentity, StaffPermission, permits } from '../domain/session-types';
import { SessionWireMapper } from '../infrastructure/session-wire-mapper';
import { BaseResponse, isSuccessResponse } from '../models/base-response';

@Injectable({ providedIn: 'root' })
export class SessionStore {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  readonly state = signal(SessionState.Loading);
  readonly staff = signal<StaffIdentity | null>(null);
  readonly notice = signal<string | null>(null);
  readonly generation = signal(0);
  readonly changed = new Subject<void>();
  private token: string | null = null;
  private boot: Promise<void> | null = null;
  csrfToken(): string | null { return this.token; }
  can(permission: StaffPermission): boolean { const staff = this.staff(); return this.state().isAuthenticated && !!staff && permits(staff.role, permission); }
  private reset(state: SessionState): number {
    this.generation.update(value => value + 1);
    this.changed.next();
    this.staff.set(null); this.token = null; this.notice.set(null); this.state.set(state);
    return this.generation();
  }
  async bootstrap(force = false): Promise<void> {
    if (this.boot && !force) return this.boot;
    const generation = this.reset(SessionState.Loading);
    this.boot = (async () => {
      try {
        const response = await firstValueFrom(this.http.get<BaseResponse<{staff: unknown}>>(`${API_BASE}/auth/session`));
        if (generation !== this.generation()) return;
        const staff = isSuccessResponse(response) ? SessionWireMapper.staff(response.data?.staff) : null;
        if (!staff) { this.state.set(SessionState.Unknown); return; }
        const token = await this.fetchCsrf();
        if (generation !== this.generation()) return;
        this.token = token; this.staff.set(staff); this.state.set(SessionState.Authenticated);
      } catch (error) {
        if (generation !== this.generation()) return;
        this.state.set(error instanceof HttpErrorResponse && error.status === 401 ? SessionState.Anonymous : SessionState.Unavailable);
      }
    })();
    return this.boot;
  }
  private async fetchCsrf(): Promise<string> {
    const response = await firstValueFrom(this.http.get<BaseResponse<unknown>>(`${API_BASE}/auth/csrf`));
    const token = isSuccessResponse(response) ? SessionWireMapper.csrf(response.data) : null;
    if (!token) throw new Error('CSRF no disponible');
    return token;
  }
  async login(login: string, password: string): Promise<boolean> {
    const generation = this.reset(SessionState.Loading);
    try {
      const token = await this.fetchCsrf();
      if (generation !== this.generation()) return false;
      this.token = token;
      const response = await firstValueFrom(this.http.post<BaseResponse<{staff: unknown; csrfToken: unknown}>>(`${API_BASE}/auth/login`, { login, password }));
      if (generation !== this.generation()) return false;
      const staff = isSuccessResponse(response) ? SessionWireMapper.staff(response.data?.staff) : null;
      const sessionToken = SessionWireMapper.csrf(response.data);
      if (!staff || !sessionToken) { this.token = null; this.state.set(SessionState.Unknown); return false; }
      this.staff.set(staff); this.token = sessionToken; this.state.set(SessionState.Authenticated);
      return true;
    } catch (error) {
      if (generation !== this.generation()) return false;
      this.token = null;
      this.state.set(error instanceof HttpErrorResponse && error.status > 0 ? SessionState.Anonymous : SessionState.Unavailable);
      this.notice.set('No se pudo iniciar sesión. Comprobá tus credenciales o intentá nuevamente.');
      return false;
    }
  }
  async logout(): Promise<void> {
    const token = this.token;
    const generation = this.reset(SessionState.Loading);
    try {
      await firstValueFrom(this.http.post<void>(`${API_BASE}/auth/logout`, {}, { headers: token ? { 'X-CSRF-Token': token } : {} }));
      if (generation === this.generation()) this.state.set(SessionState.Anonymous);
    } catch { if (generation === this.generation()) { this.state.set(SessionState.Unavailable); this.notice.set('No se pudo confirmar la revocación. Reintentá comprobar la sesión.'); } }
    await this.router.navigateByUrl('/sesion');
  }
  expire(): void { this.reset(SessionState.Expired); void this.router.navigateByUrl('/sesion'); }
  forbidden(): void { this.notice.set('Permiso insuficiente para esta operación.'); }
  landing(): string { return this.can(StaffPermission.WorkspaceRead) ? '/' : this.can(StaffPermission.ShiftReportRead) ? '/reportes' : '/sesion'; }
}
