import { Component, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { SaleSnapshotState } from '../../core/domain/sale-snapshot-state';
import { SaleSnapshotStore } from '../../core/services/sale-snapshot.store';
import { SurfaceDataSource } from '../../core/domain/data-provenance';
import { SalePresentation } from './sale-presentation';
import { SurfaceDataEvidenceComponent } from '../../shared/surface-data-evidence.component';

@Component({
  selector: 'bs-sale-ticket-snapshot',
  standalone: true,
  imports: [RouterLink, SurfaceDataEvidenceComponent],
  providers: [SaleSnapshotStore],
  template: `
    <section class="page">
      <div class="section-heading">
        <div>
          <p class="eyebrow">Ticket operativo</p>
          <h2>Detalle de venta</h2>
          <p class="lede">Comprobante operativo de sólo lectura. Revisá los artículos y cobros de esta venta.</p>
        </div>
        <a routerLink="/ventas">Volver a ventas existentes</a>
      </div>
      <bs-surface-data-evidence surfaceId="BS-S07" [source]="surfaceSource()" [datasets]="surfaceDatasets()" />
      @if (store.state().loading) {
        <div class="card" role="status"><p class="skeleton" aria-hidden="true"></p><p>{{ store.state().label }}</p></div>
      } @else if (store.state().showsDetail && store.detail(); as sale) {
        <article class="card">
          <div class="section-heading">
            <div><p class="eyebrow">Venta {{ sale.identity.saleId }}</p><h3>{{ sale.status.label }}</h3></div>
            <span class="badge" [class.ok]="sale.valid" [class.info]="!sale.valid">{{ sale.valid ? 'Evidencia comprobada' : 'Sólo consulta' }}</span>
          </div>
          <dl class="metrics"><div class="metric"><dt>Total</dt><dd>{{ amount(sale.total) }}</dd></div><div class="metric"><dt>Saldo pendiente</dt><dd>{{ amount(sale.pending) }}</dd></div><div class="metric"><dt>Fecha</dt><dd>No disponible</dd></div></dl>
          <p>{{ sale.coverage.label }}</p>
          <p><strong>Registro BlackStore:</strong> <span class="badge" [attr.data-source]="transactionSource().wire">{{ transactionSource().label }}</span></p>
          <p><strong>Origen histórico del producto:</strong> <span class="badge warn" [attr.data-source]="historicalSource.wire">{{ historicalSource.label }}</span>. Esta información no fue registrada.</p>
          <h4>Artículos</h4>
          @for (line of sale.lines; track $index) {
            <p><strong>{{ line.quantity }} × {{ line.productName }}</strong> · {{ amount(line.total) }} · <span class="sku">{{ line.sku }}</span></p>
          } @empty { <p>No hay artículos disponibles en esta lectura.</p> }
          <h4>Pagos</h4>
          @for (payment of sale.payments; track payment.paymentId) {
            <p><strong>{{ payment.method.label }} · {{ amount(payment.amount) }}</strong> · {{ payment.status.label }}</p>
          } @empty { <p>No hay pagos registrados.</p> }
          <details><summary>Evidencia técnica de la operación</summary><dl class="identity-grid">
            <div><dt>Cliente técnico</dt><dd class="sku">{{ sale.identity.clientInstanceId }}</dd></div>
            <div><dt>Dispositivo</dt><dd class="sku">{{ sale.identity.deviceId }}</dd></div>
            <div><dt>Venta</dt><dd class="sku">{{ sale.identity.saleId }}</dd></div>
            <div><dt>Operación</dt><dd class="sku">{{ sale.identity.operationId }}</dd></div>
            <div><dt>Caja</dt><dd>{{ sale.cashSessionId }}</dd></div>
            <div><dt>Cajero</dt><dd>{{ sale.cashierId }}</dd></div>
          </dl><p class="sku">Recibo {{ sale.receipt ?? 'No disponible' }} · reserva {{ sale.reservationRef ?? 'No disponible' }}</p></details>
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
  styles: [`
    details { margin: 1rem 0; }
    details .sku { overflow-wrap: anywhere; }
    @media (max-width: 480px) { .section-heading { flex-direction: column; } }
  `],
})
export class SaleTicketSnapshotComponent {
  readonly historicalSource = SurfaceDataSource.Unknown;
  readonly amount = SalePresentation.amount.bind(SalePresentation);
  readonly store = inject(SaleSnapshotStore);
  readonly states = SaleSnapshotState;
  private readonly route = inject(ActivatedRoute);
  transactionSource(): SurfaceDataSource {
    if (this.store.state() === SaleSnapshotState.Found && this.store.detail()) return SurfaceDataSource.Real;
    if (this.store.state() === SaleSnapshotState.Unavailable) return SurfaceDataSource.Unavailable;
    return SurfaceDataSource.Unknown;
  }
  surfaceSource(): SurfaceDataSource { return SurfaceDataSource.aggregate([this.transactionSource(), this.historicalSource]); }
  surfaceDatasets() { return [
    {id:'sale-detail', label:'Detalle de venta', source:this.transactionSource()},
    {id:'historical-product-provenance', label:'Origen histórico del producto', source:this.historicalSource},
  ] as const; }

  constructor() {
    this.route.paramMap.pipe(takeUntilDestroyed()).subscribe(params => this.store.loadPathSegment(params.get('saleId')));
  }
}
