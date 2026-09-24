import { HttpClient } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { API_BASE } from '../../core/api';
import { BaseResponse } from '../../core/models/base-response';

interface CatalogItem {
  sku: string;
  name: string;
  variantId: string;
}

interface CatalogData {
  version: string | null;
  stale: boolean;
  importedAt: string | null;
  validUntil: string | null;
  canonicalPath: string | null;
  items: CatalogItem[];
}

@Component({
  selector: 'bs-catalog-panel',
  standalone: true,
  template: `
    <section class="page">
      <h2>Catálogo</h2>
      <p class="lede">Proyección de solo lectura. Si el fixture está vencido o no disponible, la venta nueva se bloquea.</p>
      @if (loading()) {
        <div class="card">
          <p class="skeleton" aria-hidden="true"></p>
          <p>Cargando catálogo…</p>
        </div>
      }
      @if (catalog(); as item) {
        <div class="card">
          <p>
            Versión {{ item.version }}
            <span class="badge" [class.ok]="!item.stale" [class.warn]="item.stale">{{ item.stale ? 'vencido' : 'vigente' }}</span>
          </p>
          <p>Importado {{ item.importedAt }} · vigente hasta {{ item.validUntil }}</p>
          @if (item.stale) {
            <p class="error">Catálogo vencido. No inicies una venta nueva.</p>
          }
        </div>
        @if (item.items.length) {
          <table>
            <thead>
              <tr>
                <th>SKU</th>
                <th>Nombre</th>
                <th>Variante</th>
              </tr>
            </thead>
            <tbody>
              @for (row of item.items; track row.sku) {
                <tr>
                  <td class="sku">{{ row.sku }}</td>
                  <td>{{ row.name }}</td>
                  <td class="sku">{{ row.variantId }}</td>
                </tr>
              }
            </tbody>
          </table>
        } @else {
          <p class="empty">No hay SKUs en la proyección.</p>
        }
      } @else if (error()) {
        <div class="retry-row">
          <p class="error">{{ error() }}</p>
          <button type="button" class="ghost" (click)="load()">Reintentar</button>
        </div>
      }
    </section>
  `,
})
export class CatalogPanelComponent implements OnInit {
  private readonly http = inject(HttpClient);
  readonly catalog = signal<CatalogData | null>(null);
  readonly error = signal<string | null>(null);
  readonly loading = signal(false);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.http.get<BaseResponse<CatalogData>>(`${API_BASE}/catalog`).subscribe({
      next: (response) => {
        this.catalog.set(response.data);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Catálogo no disponible');
        this.loading.set(false);
      },
    });
  }
}
