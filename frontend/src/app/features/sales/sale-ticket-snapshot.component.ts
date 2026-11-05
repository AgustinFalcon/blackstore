import { Component, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { SaleSnapshotState } from '../../core/domain/sale-snapshot-state';
import { SaleSnapshotStore } from '../../core/services/sale-snapshot.store';

@Component({
  selector: 'bs-sale-ticket-snapshot',
  standalone: true,
  imports: [RouterLink],
  providers: [SaleSnapshotStore],
  template: `
    <section class="page">
      <div class="section-heading">
        <div>
          <p class="eyebrow">Ticket operativo</p>
          <h2>Detalle de venta</h2>
          <p class="lede">Snapshot durable de sólo lectura. Consultarlo no reserva stock, no cobra y no confirma la venta.</p>
        </div>
        <a routerLink="/ventas">Volver a ventas existentes</a>
      </div>
      @if (store.state().loading) {
        <div class="card" role="status"><p class="skeleton" aria-hidden="true"></p><p>{{ store.state().label }}</p></div>
      } @else if (store.state().showsDetail && store.detail(); as sale) {
        <article class="card">
          <div class="section-heading">
            <div><p class="eyebrow">Venta {{ sale.identity.saleId }}</p><h3>{{ sale.status.label }}</h3></div>
            <span class="badge" [class.ok]="sale.valid" [class.info]="!sale.valid">{{ sale.valid ? 'Evidencia comprobada' : 'Sólo consulta' }}</span>
          </div>
          <p>{{ sale.coverage.label }} · total {{ sale.total?.decimal ?? 'No comprobado' }} · saldo {{ sale.pending?.decimal ?? 'No comprobado' }}</p>
          <dl class="identity-grid">
            <div><dt>Cliente técnico</dt><dd class="sku">{{ sale.identity.clientInstanceId }}</dd></div>
            <div><dt>Dispositivo</dt><dd class="sku">{{ sale.identity.deviceId }}</dd></div>
            <div><dt>Venta</dt><dd class="sku">{{ sale.identity.saleId }}</dd></div>
            <div><dt>Operación</dt><dd class="sku">{{ sale.identity.operationId }}</dd></div>
            <div><dt>Caja</dt><dd>{{ sale.cashSessionId }}</dd></div>
            <div><dt>Cajero</dt><dd>{{ sale.cashierId }}</dd></div>
          </dl>
          <h4>Artículos</h4>
          @for (line of sale.lines; track $index) {
            <p>{{ line.quantity }} × {{ line.productName }} · <span class="sku">{{ line.sku }}</span> · {{ line.total?.decimal ?? 'No comprobado' }}</p>
          }
          <h4>Pagos</h4>
          @for (payment of sale.payments; track payment.paymentId) {
            <p>#{{ payment.paymentId }} · {{ payment.method.label }} · {{ payment.status.label }} · {{ payment.amount?.decimal ?? 'No comprobado' }}</p>
          } @empty { <p>No hay pagos registrados.</p> }
          <p class="sku">Receipt {{ sale.receipt ?? 'No disponible' }} · reserva {{ sale.reservationRef ?? 'No disponible' }}</p>
          <p role="status">Esta ruta no ofrece acciones mutantes. Volvé a Ventas existentes para iniciar una operación autorizada.</p>
        </article>
      } @else {
        <div class="card" role="alert">
          <h3>No pudimos mostrar el ticket</h3>
          <p>{{ store.state().label }}</p>
          @if (store.state() === states.Unavailable) { <button type="button" (click)="store.retry()">Reintentar</button> }
          <a routerLink="/ventas">Buscar en ventas existentes</a>
        </div>
      }
    </section>
  `,
})
export class SaleTicketSnapshotComponent {
  readonly store = inject(SaleSnapshotStore);
  readonly states = SaleSnapshotState;
  private readonly route = inject(ActivatedRoute);

  constructor() {
    this.route.paramMap.pipe(takeUntilDestroyed()).subscribe(params => this.store.load(params.get('saleId')));
  }
}
