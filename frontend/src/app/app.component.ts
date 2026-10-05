import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { HealthApiService } from './core/services/health-api.service';
import { isSuccessResponse } from './core/models/base-response';
import { StaffRole } from './core/domain/pos-types';

@Component({
  selector: 'bs-root',
  standalone: true,
  imports: [RouterLink, RouterLinkActive, RouterOutlet],
  template: `
    <div class="frame">
      <aside class="rail">
        <p class="brand">BlackStore</p>
        <nav>
          <a routerLink="/" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: true }">Inicio</a>
          <a routerLink="/caja" routerLinkActive="active">Caja</a>
          <a routerLink="/catalogo" routerLinkActive="active">Catálogo</a>
          <a routerLink="/ticket" routerLinkActive="active">Ticket</a>
          <a routerLink="/reportes" routerLinkActive="active">Reportes</a>
        </nav>
      </aside>
      <div class="main">
        <header>
          <h1>BlackStore POS</h1>
          <p class="role">{{ currentRole.label }} · simulador local</p>
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
        <router-outlet />
      </div>
    </div>
  `,
  styles: [
    `
      .frame {
        display: grid;
        grid-template-columns: 13rem 1fr;
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
      .banner.info {
        background: #dbeafe;
        border-color: var(--bs-info);
      }
    `,
  ],
})
export class AppComponent implements OnInit {
  readonly currentRole = StaffRole.Cashier;
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
