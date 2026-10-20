import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AllowedAction, DurableSaleState } from '../../core/domain/durable-sale';
import { PaymentMethod, PaymentStatus } from '../../core/domain/pos-types';
import { DurableSalesStore } from '../../core/services/durable-sales.store';
import { ConfirmationPhase } from '../../core/domain/confirmation-phase';
import { ReversalReason } from '../../core/domain/reversal-reason';
import { ConfirmationDialogComponent } from '../../shared/confirmation-dialog.component';

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
  selector: 'bs-durable-sales', standalone: true, imports: [FormsModule, RouterLink, ConfirmationDialogComponent],
  template: `
    <section class="page">
      <h2>Ventas existentes</h2>
      <p class="lede">Consultá y reabrí una operación con su identidad original. El simulador local sigue sin habilitar integraciones live.</p>
      <a routerLink="/ticket">Iniciar nueva venta</a>
      <div class="card">
        <label>Estado <select [(ngModel)]="filter" (ngModelChange)="store.list(null, filter)">
          <option [ngValue]="null">Todos</option>
          @for (state of states; track state.wire) { <option [ngValue]="state">{{ state.label }}</option> }
        </select></label>
        <button type="button" (click)="store.list(null, filter)" [disabled]="store.loadingList()">Reintentar consulta</button>
        @if (store.loadingList()) { <p role="status">Consultando ventas…</p> }
        @else {
          @for (sale of store.items(); track sale.identity.operationId) {
            <article>
              <p>{{ sale.status.label }} · caja {{ sale.cashSessionId }} · total {{ sale.total?.decimal ?? 'No comprobado' }}</p>
              <p class="sku">{{ sale.identity.operationId }}</p>
              <button type="button" (click)="store.open(sale.identity.operationId)" [disabled]="store.busy()">Reabrir</button>
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
          <h3>{{ sale.status.label }}</h3>
          <p>{{ sale.coverage.label }} · total {{ sale.total?.decimal ?? 'No comprobado' }} · saldo {{ sale.pending?.decimal ?? 'No comprobado' }}</p>
          <p>Caja {{ sale.cashSessionId }} · responsable de caja {{ sale.cashierId }} · actor histórico {{ sale.createdBy ?? 'No comprobado' }}</p>
          <p>Cliente <span class="sku">{{ sale.identity.clientInstanceId }}</span> · dispositivo {{ sale.identity.deviceId }}</p>
          <p>Venta <span class="sku">{{ sale.identity.saleId }}</span> · operación {{ sale.identity.operationId }}</p>
          <p>Evidencia local · receipt {{ sale.receipt ?? 'No disponible' }} · reserva {{ sale.reservationRef ?? 'No disponible' }}</p>
          @if (sale.pendingCommand; as command) { <p>{{ command.label }}. Consultá el estado; el recovery durable lo administra el backend.</p> }
          @for (line of sale.lines; track $index) { <p>{{ line.sku }} · {{ line.productName }} · {{ line.quantity }} · {{ line.total?.decimal ?? 'No comprobado' }}</p> }
          @for (payment of sale.payments; track payment.paymentId) {
            <p>Pago {{ payment.paymentId }} · {{ payment.method.label }} · {{ payment.status.label }} · {{ payment.amount?.decimal ?? 'No comprobado' }} · comisión {{ payment.fee?.decimal ?? 'No comprobado' }}</p>
            <p>{{ payment.reversibility.label }}. Tras una devolución, las nuevas reversas requieren evidencia por pago del backend.</p>
            @if (payment.reversibility.permitsReverse && store.can(actions.ReversePayment)) {
              <button type="button" (click)="requestReverse(payment.paymentId)" [disabled]="!validReversalReason()">Reversar pago {{ payment.paymentId }}</button>
            }
          }
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
})
export class DurableSalesComponent implements OnInit {
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
