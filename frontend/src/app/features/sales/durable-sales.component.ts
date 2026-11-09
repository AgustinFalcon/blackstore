import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AllowedAction, DurableSaleState } from '../../core/domain/durable-sale';
import { PaymentMethod, PaymentStatus } from '../../core/domain/pos-types';
import { DurableSalesStore } from '../../core/services/durable-sales.store';
import { ConfirmationPhase } from '../../core/domain/confirmation-phase';
import { ReversalReason } from '../../core/domain/reversal-reason';
import { ConfirmationDialogComponent } from '../../shared/confirmation-dialog.component';
import { SaleRouteId } from '../../core/domain/sale-snapshot-state';
import { SurfaceDataSource } from '../../core/domain/data-provenance';
import { SalePresentation } from './sale-presentation';
import { SurfaceDataEvidenceComponent } from '../../shared/surface-data-evidence.component';

interface DurableReversalReview {
  readonly operationId: string;
  readonly paymentId: number;
  readonly reason: ReversalReason;
}

interface DurableReleaseReview {
  readonly operationId: string;
  readonly reason: string;
}

@Component({
  selector: 'bs-durable-sales', standalone: true, imports: [FormsModule, RouterLink, ConfirmationDialogComponent, SurfaceDataEvidenceComponent],
  template: `
    <section class="page">
      <div class="section-heading"><div><p class="eyebrow">Actividad comercial</p><h2>Ventas existentes</h2>
      <p class="lede">Consultá tus ventas, revisá los cobros y continuá las operaciones pendientes.</p></div>
      <a class="primary" routerLink="/ticket">Iniciar nueva venta</a></div>
      <bs-surface-data-evidence surfaceId="BS-S08" [source]="surfaceSource()" [datasets]="surfaceDatasets()" />
      <div class="card" aria-label="Procedencia de las ventas">
        <p><strong>Registro BlackStore:</strong> <span class="badge" [attr.data-source]="store.listSource().wire">{{ store.listSource().label }}</span></p>
        <p><strong>Origen histórico del producto:</strong> <span class="badge warn" [attr.data-source]="historicalSource.wire">{{ historicalSource.label }}</span>. La operación conserva sus importes; su origen de catálogo no fue registrado.</p>
      </div>
      <div class="card">
        <label>Estado <select [(ngModel)]="filter" (ngModelChange)="store.list(null, filter)">
          <option [ngValue]="null">Todos</option>
          @for (state of states; track state.wire) { <option [ngValue]="state">{{ state.label }}</option> }
        </select></label>
        <button type="button" (click)="store.list(null, filter)" [disabled]="store.loadingList()">Reintentar consulta</button>
        @if (store.loadingList()) { <p role="status">Consultando ventas…</p> }
        @else {
          @for (sale of store.items(); track sale.identity.operationId) {
            <article class="sale-row">
              <div class="section-heading"><h3>{{ sale.status.label }}</h3><strong class="money">{{ formatAmount(sale.total) }}</strong></div>
              <p>Caja {{ sale.cashSessionId }} · Fecha: No disponible</p>
              <details><summary>Identidad de la venta</summary><p class="sku">{{ sale.identity.operationId }}</p></details>
              <a routerLink="/ticket" [queryParams]="{operationId:sale.identity.operationId}">Continuar en punto de venta</a>
              <a [routerLink]="['/ticket', ticketPathSegment(sale.identity.saleId)]">Ver ticket</a>
            </article>
          } @empty { <p>No hay ventas visibles en esta página.</p> }
          @if (store.nextCursor(); as cursor) { <button type="button" (click)="store.list(cursor, filter)">Página siguiente</button> }
        }
      </div>
      @if (store.notice()) { <p class="banner warn" role="alert">{{ store.notice() }}</p> }
      <p role="status">{{ store.commands.runtime.notice() }}</p>
      <button type="button" (click)="store.commands.runtime.refresh()" [disabled]="store.busy()">Comprobar contexto y evidencia</button>
      @if (store.sales.unresolved()) {
        <button type="button" (click)="store.consultReceipt()" [disabled]="store.busy()">Consultar admisión pendiente</button>
      }
      @if (store.commands.unresolved(); as command) {
        <p>Comando {{ command.commandId }} pendiente de comprobación.</p>
        <button type="button" (click)="store.consultReceipt()" [disabled]="store.busy()">Consultar recibo</button>
      }
      @if (store.busy()) { <p role="status">Comprobando operación…</p> }
      @if (store.detail(); as sale) {
        <div class="card">
          <div class="section-heading"><h3>{{ sale.status.label }}</h3><span class="badge">{{ sale.coverage.label }}</span></div>
          <p><strong>Lectura de la operación:</strong> <span class="badge" [attr.data-source]="store.detailSource().wire">{{ store.detailSource().label }}</span></p>
          <dl class="metrics"><div class="metric"><dt>Total</dt><dd>{{ formatAmount(sale.total) }}</dd></div><div class="metric"><dt>Saldo pendiente</dt><dd>{{ formatAmount(sale.pending) }}</dd></div><div class="metric"><dt>Fecha</dt><dd>No disponible</dd></div></dl>
          <h4>Artículos</h4>
          @for (line of sale.lines; track $index) { <p><strong>{{ line.quantity }} × {{ line.productName }}</strong> · {{ formatAmount(line.total) }} <small class="sku">{{ line.sku }}</small></p> }
          @empty { <p>No hay artículos disponibles en esta lectura.</p> }
          <h4>Pagos registrados</h4>
          @if (sale.pendingCommand; as command) { <p>{{ command.label }}. Consultá el estado; el recovery durable lo administra el backend.</p> }
          @for (payment of sale.payments; track payment.paymentId) {
            <p><strong>{{ payment.method.label }} · {{ formatAmount(payment.amount) }}</strong> · {{ payment.status.label }} · comisión {{ formatAmount(payment.fee) }}</p>
            <p>{{ payment.reversibility.label }}. Tras una devolución, las nuevas reversas requieren evidencia por pago del backend.</p>
            @if (payment.reversibility.permitsReverse && store.can(actions.ReversePayment)) {
              <button type="button" (click)="requestReverse(payment.paymentId)" [disabled]="!validReversalReason()">Reversar pago {{ payment.paymentId }}</button>
            }
          } @empty { <p>No hay pagos registrados.</p> }
          <details><summary>Evidencia técnica de la operación</summary>
            <p>Caja {{ sale.cashSessionId }} · responsable de caja {{ sale.cashierId }} · actor histórico {{ sale.createdBy ?? 'No disponible' }}</p>
            <p>Cliente técnico <span class="sku">{{ sale.identity.clientInstanceId }}</span> · dispositivo {{ sale.identity.deviceId }}</p>
            <p>Venta <span class="sku">{{ sale.identity.saleId }}</span> · operación {{ sale.identity.operationId }}</p>
            <p>Recibo {{ sale.receipt ?? 'No disponible' }} · reserva {{ sale.reservationRef ?? 'No disponible' }}</p>
          </details>
          @if (!sale.status.acceptsCommands || !sale.valid) { <p role="status">Sólo consulta. Reintentá consultar o solicitá reconciliación a una persona autorizada.</p> }
          <label>Motivo de operación <input [(ngModel)]="reason" aria-describedby="durable-reversal-reason-guidance" /></label>
          <p id="durable-reversal-reason-guidance">Para reversar un pago: {{ reversalReasonGuidance }}</p>
          @if (store.can(actions.CapturePayment)) {
            <label>Importe a completar <input type="number" min="0.01" [(ngModel)]="amount" /></label>
            <p>Las comisiones pagadas se registran por el circuito operativo del titular.</p>
            <label>Medio <select [(ngModel)]="method">@for (option of methods; track option.wire) { <option [ngValue]="option">{{ option.label }}</option> }</select></label>
            <button type="button" (click)="store.execute(actions.CapturePayment, reason, amount, method)">Completar pago</button>
          }
          @if (store.can(actions.Commit)) { <button type="button" (click)="store.execute(actions.Commit, reason)">Confirmar venta</button> }
          @if (store.can(actions.Release)) { <button type="button" (click)="requestRelease()">Liberar reserva</button> }
          <button type="button" (click)="store.open(sale.identity.operationId)">Consultar estado</button>
          <a routerLink="/ticket" [queryParams]="{operationId:sale.identity.operationId}">Continuar operación en punto de venta</a>
          <button type="button" (click)="store.close()">Cerrar detalle</button>
        </div>
      }
      <bs-confirmation-dialog [phase]="reversalPhase()" title="Confirmar reversa" titleId="sale-reversal-title"
        [description]="reversalDescription()" [detail]="reversalReview() ? 'Motivo: ' + reversalReview()!.reason.wire : ''"
        confirmLabel="Confirmar reversa" cancelLabel="Cancelar"
        (confirmed)="confirmReverse()" (cancelled)="cancelReverse()" />
      <bs-confirmation-dialog [phase]="releasePhase()" title="Liberar reserva" titleId="existing-sale-release-title"
        description="El stock reservado volverá a quedar disponible. El historial de pagos no se elimina."
        [detail]="releaseReview() ? 'Operación: ' + releaseReview()!.operationId : ''"
        confirmLabel="Liberar reserva" cancelLabel="Mantener reserva"
        (confirmed)="confirmRelease()" (cancelled)="cancelRelease()" />
    </section>
  `,
  styles: [`
    .sale-row { padding: 1rem 0; border-top: 1px solid var(--bs-line); }
    .sale-row a { display: inline-flex; margin: .5rem 1rem .25rem 0; }
    .sale-row h3 { margin: 0; }
    details { margin: .75rem 0; }
    details .sku { overflow-wrap: anywhere; }
    @media (max-width: 480px) { .section-heading { flex-direction: column; } }
  `],
})
export class DurableSalesComponent implements OnInit {
  readonly historicalSource = SurfaceDataSource.Unknown;
  surfaceSource(): SurfaceDataSource { return SurfaceDataSource.aggregate([this.store.listSource(), this.historicalSource]); }
  surfaceDatasets() { return [
    {id:'sale-list', label:'Listado de ventas', source:this.store.listSource()},
    {id:'historical-product-provenance', label:'Origen histórico del producto', source:this.historicalSource},
  ] as const; }
  readonly formatAmount = SalePresentation.amount.bind(SalePresentation);
  ticketPathSegment(raw: string): string { return SaleRouteId.fromWire(raw)?.pathSegment ?? 'id:'; }
  readonly store = inject(DurableSalesStore);
  readonly actions = AllowedAction;
  readonly captured = PaymentStatus.Captured;
  readonly states = DurableSaleState.values;
  readonly methods = PaymentMethod.selectable;
  filter: DurableSaleState | null = null;
  reason = '';
  amount = 0;
  method = PaymentMethod.Cash;
  readonly reversalPhase = signal(ConfirmationPhase.Idle);
  readonly releasePhase = signal(ConfirmationPhase.Idle);
  readonly reversalReview = signal<DurableReversalReview | null>(null);
  readonly reversalReasonGuidance = ReversalReason.guidance;
  readonly releaseReview = signal<DurableReleaseReview | null>(null);
  validReversalReason(): boolean { return ReversalReason.fromInput(this.reason) !== null; }
  ngOnInit(): void { this.store.list(); }
  requestReverse(paymentId: number): void {
    const sale = this.store.detail();
    const reason = ReversalReason.fromInput(this.reason);
    if (!sale || !reason || !this.store.can(this.actions.ReversePayment)) return;
    this.reversalReview.set(Object.freeze({ operationId: sale.identity.operationId, paymentId, reason }));
    this.reversalPhase.set(ConfirmationPhase.Reviewing);
  }
  cancelReverse(): void {
    this.reversalReview.set(null);
    this.reversalPhase.set(ConfirmationPhase.Idle);
  }
  reversalDescription(): string {
    const review = this.reversalReview();
    return review == null ? '' : `Vas a reversar el pago ${review.paymentId} de la venta abierta.`;
  }
  confirmReverse(): void {
    const review = this.reversalReview();
    if (!this.reversalPhase().isReviewing || !review || this.store.detail()?.identity.operationId !== review.operationId ||
        !this.store.can(this.actions.ReversePayment)) { this.cancelReverse(); return; }
    this.cancelReverse();
    this.store.execute(this.actions.ReversePayment, review.reason.wire, undefined, this.method, review.paymentId);
  }
  requestRelease(): void {
    const sale = this.store.detail();
    if (!sale || !this.store.can(this.actions.Release)) return;
    this.releaseReview.set(Object.freeze({ operationId: sale.identity.operationId, reason: this.reason.trim() }));
    this.releasePhase.set(ConfirmationPhase.Reviewing);
  }
  cancelRelease(): void { this.releaseReview.set(null); this.releasePhase.set(ConfirmationPhase.Idle); }
  confirmRelease(): void {
    const review = this.releaseReview();
    if (!this.releasePhase().isReviewing || !review || this.store.detail()?.identity.operationId !== review.operationId ||
        !this.store.can(this.actions.Release)) { this.cancelRelease(); return; }
    this.cancelRelease();
    this.store.execute(this.actions.Release, review.reason);
  }
}
