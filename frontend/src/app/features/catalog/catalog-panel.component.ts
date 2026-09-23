import { HttpClient } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { API_BASE } from '../../core/api';
import { BaseResponse } from '../../core/models/base-response';

interface CatalogData {
  version: string | null;
  stale: boolean;
  validUntil: string | null;
  canonicalPath: string | null;
}

@Component({
  selector: 'bs-catalog-panel',
  standalone: true,
  template: `
    <section>
      <h2>Catálogo</h2>
      <p>Proyección de solo lectura. Si el fixture está vencido o no disponible, la venta nueva se bloquea.</p>
      @if (catalog(); as item) {
        <p>Versión {{ item.version }} · {{ item.stale ? 'vencido' : 'vigente' }}</p>
        <p>Vigente hasta {{ item.validUntil }}</p>
      } @else if (error()) {
        <p class="error">{{ error() }}</p>
      }
    </section>
  `,
  styles: [`.error { color: #f87171; }`],
})
export class CatalogPanelComponent implements OnInit {
  private readonly http = inject(HttpClient);
  readonly catalog = signal<CatalogData | null>(null);
  readonly error = signal<string | null>(null);

  ngOnInit(): void {
    this.http.get<BaseResponse<CatalogData>>(`${API_BASE}/catalog`).subscribe({
      next: (response) => this.catalog.set(response.data),
      error: () => this.error.set('Catálogo no disponible'),
    });
  }
}
