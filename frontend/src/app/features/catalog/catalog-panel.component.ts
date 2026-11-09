import { HttpClient } from '@angular/common/http';
import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { SessionStore } from '../../core/services/session.store';
import { API_BASE } from '../../core/api';
import { BaseResponse } from '../../core/models/base-response';
import { isSuccessResponse } from '../../core/models/base-response';
import { CatalogItem, CatalogSnapshot, CatalogSource } from '../../core/domain/catalog';
import { CatalogSnapshotWire } from '../../core/models/catalog-wire';
import { CatalogWireMapper } from '../../core/infrastructure/catalog-wire-mapper';
import { Subscription } from 'rxjs';
import { SurfaceDataSource } from '../../core/domain/data-provenance';
import { SurfaceDataEvidenceComponent } from '../../shared/surface-data-evidence.component';

@Component({
  selector: 'bs-catalog-panel',
  standalone: true,
  imports: [FormsModule, RouterLink, SurfaceDataEvidenceComponent],
  template: `
    <section class="page">
      <div class="section-heading"><div><p class="eyebrow">Operación de mostrador</p><h2>Catálogo</h2><p class="lede">Consultá precios y disponibilidad antes de iniciar una venta.</p></div><a class="primary" routerLink="/ticket">Nueva venta</a></div>
      <bs-surface-data-evidence surfaceId="BS-S05" [source]="observedSource()" [datasets]="surfaceDatasets()" />
      @if (loading()) {
        <div class="card">
          <p class="skeleton" aria-hidden="true"></p>
          <p>Cargando catálogo…</p>
        </div>
      }
      @if (catalog(); as item) {
        <div class="metrics" aria-label="Resumen del catálogo">
          <dl class="card metric"><dt>Productos visibles</dt><dd>{{ filteredItems().length }}</dd></dl>
          <dl class="card metric"><dt>Estado</dt><dd>{{ item.freshness.label }}</dd></dl>
          <dl class="card metric"><dt>Última actualización</dt><dd>{{ date(item.importedAt) }}</dd></dl>
        </div>
        <div class="card catalog-status">
          <p>
            Versión {{ item.version }}
            <span class="badge" [class.ok]="item.freshness.allowsSelection" [class.warn]="!item.freshness.allowsSelection">{{ item.freshness.label }}</span>
          </p>
          <p><strong>Fuente:</strong> {{ item.source.label }}</p>
          @if (item.source === localFixture) { <p class="error">Datos de demostración local: no acreditan stock ni disponibilidad de StoreCore.</p> }
          <p>Importado {{ date(item.importedAt) }} · vigente hasta {{ date(item.validUntil) }}</p>
          @if (!item.freshness.allowsSelection || !item.source.available) {
            <p class="error">Catálogo sin autoridad vigente. No inicies una venta nueva.</p>
          }
        </div>
        @if (item.items.length) {
          <div class="catalog-toolbar"><label for="catalog-search">Buscar producto</label><input id="catalog-search" type="search" [(ngModel)]="query" placeholder="Nombre, SKU o variante" autocomplete="off"><span aria-live="polite">{{ filteredItems().length }} resultados</span></div>
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
                  <td class="sku">{{ row.priceVersion || 'No informada por catálogo' }}</td>
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
export class CatalogPanelComponent implements OnInit, OnDestroy {
  private readonly http = inject(HttpClient);
  private readonly identity = inject(SessionStore);
  private loadEpoch = 0;
  private request: Subscription | null = null;
  readonly catalog = signal<CatalogSnapshot | null>(null);
  readonly error = signal<string | null>(null);
  readonly loading = signal(false);
  query = '';
  readonly localFixture = CatalogSource.LocalFixture;
  observedSource(): SurfaceDataSource {
    if (this.error()) return SurfaceDataSource.Unavailable;
    return this.catalog()?.source.surfaceSource ?? SurfaceDataSource.Unknown;
  }
  surfaceDatasets() { return [{id:'catalog', label:'Catálogo', source:this.observedSource()}] as const; }
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
    this.identity.changed.pipe(takeUntilDestroyed()).subscribe(() => {
      this.loadEpoch++;
      this.request?.unsubscribe(); this.request = null;
      this.catalog.set(null); this.error.set(null); this.loading.set(false);
    });
  }

  ngOnInit(): void {
    this.load();
  }

  ngOnDestroy(): void { this.loadEpoch++; this.request?.unsubscribe(); this.request = null; }

  load(): void {
    const generation = this.identity.generation();
    const epoch = ++this.loadEpoch;
    const current = () => generation === this.identity.generation() && epoch === this.loadEpoch;
    this.request?.unsubscribe();
    this.catalog.set(null);
    this.loading.set(true);
    this.error.set(null);
    this.request = this.http.get<BaseResponse<CatalogSnapshotWire>>(`${API_BASE}/catalog`).subscribe({
      next: (response) => {
        if (!current()) return;
        const snapshot = isSuccessResponse(response) ? CatalogWireMapper.snapshot(response.data) : null;
        this.catalog.set(snapshot);
        if (!snapshot) this.error.set('El catálogo respondió con un formato no reconocido. No inicies una venta nueva.');
        this.loading.set(false);
      },
      error: () => {
        if (!current()) return;
        this.catalog.set(null);
        this.error.set('Catálogo no disponible');
        this.loading.set(false);
      },
    });
  }
}
