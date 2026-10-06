import { Component, OnInit, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { StaffPermission } from '../../../core/domain/session-types';
import { CounterContextService } from '../../../core/services/counter-context.service';
import { SessionStore } from '../../../core/services/session.store';

@Component({
  selector: 'bs-pos-shell',
  standalone: true,
  imports: [RouterLink],
  template: `
    <section class="page">
      <h2>Inicio</h2>
      <p class="lede">Consola de mostrador. El catálogo es la proyección local. StoreCore no se llama desde el browser.</p>
      @if (counter.loading()) {
        <p class="skeleton" aria-hidden="true"></p>
        <p>Cargando puesto…</p>
      } @else {
        <div class="card">
          <p>
            Catálogo
            @if (counter.catalog(); as snapshot) {
              <span class="sku">{{ snapshot.version }}</span>
              <span class="badge" [class.ok]="!snapshot.stale" [class.warn]="snapshot.stale">{{ snapshot.stale ? 'vencido' : 'vigente' }}</span>
            } @else {
              <span class="badge warn">no disponible</span>
            }
          </p>
          @if (counter.openSession(); as session) {
            <p>Caja abierta · sesión <span class="sku">{{ session.id }}</span> · terminal {{ session.terminalId }}</p>
          } @else {
            <p class="empty">No hay sesión de caja abierta.</p>
          }
          <p>Persistencia <span class="sku">{{ counter.persistence().label }}</span></p>
          @if (counter.blockReason(); as reason) {
            <p class="banner warn" role="status">{{ reason }}</p>
          }
          <p class="actions">
            <a routerLink="/caja" class="primary">Caja</a>
            <a routerLink="/ticket" class="ghost">Ticket</a>
            @if (session.can(permissions.SaleRead)) { <a routerLink="/ventas" class="ghost">Reabrir venta</a> }
            <a routerLink="/catalogo" class="ghost">Catálogo</a>
            @if (session.can(permissions.ShiftReportRead)) {
              <a routerLink="/reportes" class="ghost">Reportes</a>
            }
          </p>
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

  ngOnInit(): void {
    this.counter.load();
  }
}
