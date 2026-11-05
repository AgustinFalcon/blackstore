import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { HealthApiService } from './core/services/health-api.service';
import { isSuccessResponse } from './core/models/base-response';
import { SessionStore } from './core/services/session.store';
import { StaffPermission } from './core/domain/session-types';

@Component({
  selector: 'bs-root',
  standalone: true,
  imports: [RouterLink, RouterLinkActive, RouterOutlet],
  template: `
    <div class="frame">
      <a class="skip-link" href="#pos-content">Ir al contenido</a><aside class="rail">
        <p class="brand">BlackStore</p>
        <nav aria-label="Navegación principal">
          @if (session.can(permissions.WorkspaceRead)) { <a routerLink="/" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: true }">Inicio</a> }
          @if (session.can(permissions.CashSessionList)) { <a routerLink="/caja" routerLinkActive="active">Caja</a> }
          @if (session.can(permissions.CatalogRead)) { <a routerLink="/catalogo" routerLinkActive="active">Catálogo</a> }
          @if (session.can(permissions.SaleReserve)) { <a routerLink="/ticket" routerLinkActive="active">Ticket</a> }
          @if (session.can(permissions.SaleRead)) { <a routerLink="/ventas" routerLinkActive="active">Reabrir venta</a> }
          @if (session.can(permissions.ShiftReportRead)) { <a routerLink="/reportes" routerLinkActive="active">Reportes</a> }
          <a routerLink="/sesion">Sesión</a>
          <a href="/demo">Ver demostración</a>
        </nav>
      </aside>
      <main class="main" id="pos-content" tabindex="-1">
        <header>
          <h1>BlackStore POS</h1>
          @if (session.staff(); as staff) {
            <p class="role">{{ staff.displayName }} · {{ staff.role.label }}</p>
            <button type="button" (click)="session.logout()">Cerrar sesión</button>
          } @else { <p class="role">{{ session.state().label }}</p> }
        </header>
        @if (healthLoading()) {
          <p class="banner info" role="status">Comprobando salud del backend…</p>
        }
        @if (blocked()) {
          <p class="banner" role="status">Integración StoreCore bloqueada — simulador local</p>
        }
        @if (backendDown()) {
          <p class="banner warn" role="alert">Backend no disponible en :8081</p>
        }
        @if (session.notice()) { <p class="banner warn" role="alert">{{ session.notice() }}</p> }
        <router-outlet />
      </main>
    </div>
  `,
  styles: [
    `
      .frame {
        display: grid;
        grid-template-columns: 13rem minmax(0,1fr);
        min-height: 100vh;
      }
      .rail {
        background: var(--bs-theme);
        color: var(--bs-theme-ink);
        padding: 1.25rem 1rem;
      }
      .brand {
        margin: 0 0 1.5rem;
        font-weight: 650;
      }
      nav {
        display: flex;
        flex-direction: column;
        gap: 0.35rem;
      }
      nav a {
        color: inherit;
        text-decoration: none;
        border-radius: 8px;
        padding: 0.6rem 0.75rem;
        min-height: 44px;
        box-sizing: border-box;
      }
      nav a.active,
      nav a:hover {
        background: rgba(255, 255, 255, 0.1);
      }
      .main {
        padding: 1.25rem 1.5rem 2rem;
        min-width:0; overflow-wrap:anywhere;
      }
      header {
        display: flex;
        justify-content: space-between;
        align-items: baseline;
        gap: 1rem;
        flex-wrap: wrap;
      }
      h1 {
        margin: 0;
        font-size: 1.375rem;
        font-weight: 650;
      }
      .role {
        margin: 0;
        color: #52525b;
        white-space: normal;
      }
      .banner {
        margin: 1rem 0;
        padding: 0.75rem 1rem;
        border-radius: 8px;
        background: #e0e7ff;
        border: 1px solid #1d4ed8;
      }
      .banner.warn {
        background: #fef3c7;
        border-color: var(--bs-warning);
      }
      .skip-link{position:absolute;top:-100px;left:1rem;z-index:50;background:white;padding:1rem}.skip-link:focus{top:1rem}
      a:focus-visible,button:focus-visible{outline:3px solid #1d4ed8;outline-offset:3px}
      @media(max-width:760px){.frame{grid-template-columns:minmax(0,1fr)}.rail{padding:1rem}.brand{margin-bottom:.75rem}nav{flex-direction:row;flex-wrap:wrap;gap:.25rem}nav a{flex:1 1 8rem;min-width:0} .main{padding:1rem}header{align-items:center}header h1{flex:1 1 100%}}
      @media print{.rail,header,.skip-link{display:none}.frame{display:block}.main{padding:0}}
      .banner.info {
        background: #dbeafe;
        border-color: var(--bs-info);
      }
    `,
  ],
})
export class AppComponent implements OnInit {
  readonly session = inject(SessionStore);
  readonly permissions = StaffPermission;
  private readonly healthApi = inject(HealthApiService);
  readonly blocked = signal(true);
  readonly backendDown = signal(false);
  readonly healthLoading = signal(true);

  ngOnInit(): void {
    this.healthApi.getHealth().subscribe({
      next: (response) => {
        this.healthLoading.set(false);
        this.backendDown.set(false);
        this.blocked.set(!isSuccessResponse(response) || !response.data.storeCoreIntegrationEnabled);
      },
      error: () => {
        this.healthLoading.set(false);
        this.backendDown.set(true);
        this.blocked.set(true);
      },
    });
  }
}
