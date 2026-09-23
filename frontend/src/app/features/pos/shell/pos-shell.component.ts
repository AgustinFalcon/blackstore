import { Component, OnInit, inject, signal } from '@angular/core';
import { HealthApiService, HealthData } from '../../../core/services/health-api.service';
import { isSuccessResponse } from '../../../core/models/base-response';

@Component({
  selector: 'bs-pos-shell',
  standalone: true,
  template: `
    <main class="shell">
      <header>
        <h1>BlackStore POS</h1>
        <p class="subtitle">Consola de mostrador — bounded context autónomo</p>
      </header>
      <section class="status" aria-live="polite">
        @if (health(); as h) {
          <p>Servicio: {{ h.service }}</p>
          <p>Integración StoreCore: {{ h.storeCoreIntegrationEnabled ? 'habilitada' : 'bloqueada' }}</p>
        } @else if (error()) {
          <p class="error">{{ error() }}</p>
        } @else {
          <p>Conectando con backend local…</p>
        }
      </section>
    </main>
  `,
  styles: [
    `
      .shell {
        min-height: 100vh;
        padding: 2rem;
      }
      header h1 {
        margin: 0 0 0.25rem;
        font-size: 1.75rem;
      }
      .subtitle {
        margin: 0 0 1.5rem;
        opacity: 0.75;
      }
      .status {
        padding: 1rem 1.25rem;
        border: 1px solid #2a3441;
        border-radius: 8px;
        background: #151b23;
      }
      .error {
        color: #f87171;
      }
    `,
  ],
})
export class PosShellComponent implements OnInit {
  private readonly healthApi = inject(HealthApiService);

  readonly health = signal<HealthData | null>(null);
  readonly error = signal<string | null>(null);

  ngOnInit(): void {
    this.healthApi.getHealth().subscribe({
      next: (response) => {
        if (isSuccessResponse(response)) {
          this.health.set(response.data);
        } else {
          this.error.set(response.message ?? response.errorCode ?? 'Error desconocido');
        }
      },
      error: () => this.error.set('Backend no disponible (esperado hasta levantar API en :8081)'),
    });
  }
}
