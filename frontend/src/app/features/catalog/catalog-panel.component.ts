import { HttpClient } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { SessionStore } from '../../core/services/session.store';
import { API_BASE } from '../../core/api';
import { BaseResponse } from '../../core/models/base-response';

interface CatalogItem {
  sku: string;
  name: string;
  variantId: string;
  priceVersion: string | null;
  unitPrice: number | null;
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
  imports: [FormsModule, RouterLink],
  template: `
    <section class="page">
      <div class="section-heading"><div><p class="eyebrow">Operación de mostrador</p><h2>Catálogo disponible</h2><p class="lede">Consultá precios y disponibilidad antes de iniciar una venta.</p></div><a class="primary" routerLink="/ticket">Nueva venta</a></div>
      @if (loading()) {
        <div class="card">
          <p class="skeleton" aria-hidden="true"></p>
          <p>Cargando catálogo…</p>
        </div>
      }
      @if (catalog(); as item) {
        <div class="metrics" aria-label="Resumen del catálogo">
          <dl class="card metric"><dt>Productos visibles</dt><dd>{{ filteredItems().length }}</dd></dl>
          <dl class="card metric"><dt>Estado</dt><dd>{{ item.stale ? 'Requiere actualización' : 'Listo para vender' }}</dd></dl>
          <dl class="card metric"><dt>Última actualización</dt><dd>{{ date(item.importedAt) }}</dd></dl>
        </div>
        <div class="card catalog-status">
          <p>
            Versión {{ item.version }}
            <span class="badge" [class.ok]="!item.stale" [class.warn]="item.stale">{{ item.stale ? 'vencido' : 'vigente' }}</span>
          </p>
          <p>Importado {{ date(item.importedAt) }} · vigente hasta {{ date(item.validUntil) }}</p>
          @if (item.stale) {
            <p class="error">Catálogo vencido. No inicies una venta nueva.</p>
          }
        </div>
        @if (item.items.length) {
          <div class="toolbar"><label for="catalog-search">Buscar producto</label><input id="catalog-search" type="search" [(ngModel)]="query" placeholder="Nombre, SKU o variante" autocomplete="off"><span aria-live="polite">{{ filteredItems().length }} resultados</span></div>
          <div class="table-wrap"><table>
            <thead>
              <tr>
                <th>SKU</th>
                <th>Nombre</th>
                <th>Variante</th>
                <th>Versión de precio</th>
                <th>Precio unitario</th>
              </tr>
            </thead>
            <tbody>
              @for (row of filteredItems(); track row.sku) {
                <tr>
                  <td class="sku">{{ row.sku }}</td>
                  <td>{{ row.name }}</td>
                  <td class="sku">{{ row.variantId }}</td>
                  <td class="sku">{{ row.priceVersion || 'la carga el cajero' }}</td>
                  <td class="money">{{ money(row.unitPrice) }}</td>
                </tr>
              }
            </tbody>
          </table></div>
          @if (!filteredItems().length) { <div class="card empty"><p>No encontramos productos con “{{ query }}”.</p><button type="button" class="ghost" (click)="query = ''">Limpiar búsqueda</button></div> }
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
  query = '';
  filteredItems():readonly CatalogItem[] {
    const needle = this.query.trim().toLocaleLowerCase('es-AR');
    const rows = this.catalog()?.items ?? [];
    return needle ? rows.filter(row => `${row.name} ${row.sku} ${row.variantId}`.toLocaleLowerCase('es-AR').includes(needle)) : rows;
  }
  private readonly currency = new Intl.NumberFormat('es-AR', {style:'currency', currency:'ARS'});
  private readonly dateTime = new Intl.DateTimeFormat('es-AR', {dateStyle:'medium', timeStyle:'short'});
  money(value:number|null):string { return value === null ? 'No informado' : this.currency.format(value); }
  date(value:string|null):string { if (!value) return 'No informada'; const parsed=new Date(value); return Number.isNaN(parsed.valueOf()) ? 'No informada' : this.dateTime.format(parsed); }

  constructor() {
    inject(SessionStore).changed.pipe(takeUntilDestroyed()).subscribe(() => {
      this.catalog.set(null); this.error.set(null); this.loading.set(false);
    });
  }

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
