import { Component, OnInit, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { StaffPermission } from '../../../core/domain/session-types';
import { CounterContextService } from '../../../core/services/counter-context.service';
import { SessionStore } from '../../../core/services/session.store';
import { SurfaceDataEvidenceComponent } from '../../../shared/surface-data-evidence.component';

@Component({
  selector: 'bs-pos-shell',
  standalone: true,
  imports: [RouterLink, SurfaceDataEvidenceComponent],
  template: `
    <section class="page">
      <div class="section-heading"><div><p class="eyebrow">Operación presencial</p><h2>Panel del mostrador</h2><p class="lede">Revisá la caja y el catálogo antes de comenzar una venta.</p></div>
        @if (!counter.blockReason()) { <a routerLink="/ticket" class="primary">Nueva venta</a> }
      </div>
      <bs-surface-data-evidence surfaceId="BS-S02" [source]="counter.surfaceSource()" [datasets]="surfaceDatasets()" />
      @if (counter.loading()) {
        <div class="card" role="status"><p class="skeleton" aria-hidden="true"></p><p>Preparando el puesto…</p></div>
      } @else {
        <div class="metrics" aria-label="Resumen operativo">
          <article class="card metric"><span>Caja</span>@if (counter.openSession(); as opened) { <strong>Abierta</strong><small>Terminal {{ opened.terminalId }}</small> } @else { <strong>Sin abrir</strong><small>Necesaria para vender</small> }</article>
          <article class="card metric"><span>Catálogo</span>@if (counter.catalog(); as snapshot) { <strong>{{ snapshot.freshness.label }}</strong><small>{{ snapshot.source.label }} · {{ snapshot.items.length }} productos</small> } @else { <strong>No disponible</strong><small>Actualizá antes de vender</small> }</article>
          <article class="card metric"><span>Operación</span><strong>{{ counter.blockReason() ? 'Requiere atención' : 'Lista' }}</strong><small>{{ counter.surfaceSource().label }}</small></article>
        </div>
        <div class="card">
          @if (counter.openSession(); as session) {
            <p><b>Caja lista para operar</b> · terminal {{ session.terminalId }}</p>
          } @else {
            <p class="empty">Abrí una caja para iniciar ventas y registrar movimientos.</p>
          }
          @if (counter.blockReason(); as reason) {
            <p class="banner warn" role="status">{{ reason }}</p>
          }
          <nav class="actions" aria-label="Accesos operativos">
            <a routerLink="/caja" class="primary">Gestionar caja</a>
            <a routerLink="/ticket" class="ghost">Nueva venta</a>
            @if (session.can(permissions.SaleRead)) { <a routerLink="/ventas" class="ghost">Ventas existentes</a> }
            <a routerLink="/catalogo" class="ghost">Consultar catálogo</a>
            @if (session.can(permissions.ShiftReportRead)) {
              <a routerLink="/reportes" class="ghost">Ver reportes</a>
            }
          </nav>
          <details class="advanced"><summary>Información técnica del puesto</summary>
            <p>Persistencia: {{ counter.persistence().label }} · {{ counter.readiness().label }}</p>
            <p>Procedencia agregada: {{ counter.surfaceSource().label }}</p>
            @if (counter.catalog(); as snapshot) { <p>Catálogo {{ snapshot.version ?? 'sin versión' }} · {{ snapshot.source.label }}</p> }
          </details>
        </div>
      }
    </section>
  `,
  styles: [
    `
      .actions {
        display: flex;
        gap: 0.75rem;
        flex-wrap: wrap;
      }
      a {
        display: inline-flex;
        align-items: center;
        text-decoration: none;
        box-sizing: border-box;
      }
    `,
  ],
})
export class PosShellComponent implements OnInit {
  readonly counter = inject(CounterContextService);
  readonly session = inject(SessionStore);
  readonly permissions = StaffPermission;
  surfaceDatasets() { return [
    {id:'workspace-context', label:'Puesto y persistencia', source:this.counter.readiness().source},
    {id:'catalog-summary', label:'Catálogo', source:this.counter.catalog()?.source.surfaceSource ?? this.counter.surfaceSource()},
  ] as const; }

  ngOnInit(): void {
    this.counter.load();
  }
}
