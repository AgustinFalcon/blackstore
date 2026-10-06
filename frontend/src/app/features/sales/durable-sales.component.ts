import { Component, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AllowedAction, DurableSaleState } from '../../core/domain/durable-sale';
import { PaymentMethod, PaymentStatus } from '../../core/domain/pos-types';
import { DurableSalesStore } from '../../core/services/durable-sales.store';

@Component({
  selector: 'bs-durable-sales', standalone: true, imports: [FormsModule, RouterLink],
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
            @if (payment.status === captured && store.can(actions.ReversePayment)) {
              <button type="button" (click)="store.execute(actions.ReversePayment, reason, undefined, undefined, method, payment.paymentId)" [disabled]="!reason.trim()">Reversar pago {{ payment.paymentId }}</button>
            }
          }
          @if (!sale.status.acceptsCommands || !sale.valid) { <p role="status">Sólo consulta. Reintentá consultar o solicitá reconciliación a una persona autorizada.</p> }
          <label>Motivo de operación <input [(ngModel)]="reason" /></label>
          @if (store.can(actions.CapturePayment)) {
            <label>Importe a completar <input type="number" min="0.01" [(ngModel)]="amount" /></label>
            <label>Comisión <input type="number" min="0" [(ngModel)]="fee" /></label>
            <label>Medio <select [(ngModel)]="method">@for (option of methods; track option.wire) { <option [ngValue]="option">{{ option.label }}</option> }</select></label>
            <button type="button" (click)="store.execute(actions.CapturePayment, reason, amount, fee, method)">Completar pago</button>
          }
          @if (store.can(actions.Commit)) { <button type="button" (click)="store.execute(actions.Commit, reason)">Confirmar venta</button> }
          @if (store.can(actions.Release)) { <button type="button" (click)="store.execute(actions.Release, reason)">Liberar reserva</button> }
          <button type="button" (click)="store.open(sale.identity.operationId)">Consultar estado</button>
          <button type="button" (click)="store.close()">Cerrar detalle</button>
        </div>
      }
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
  fee = 0;
  method = PaymentMethod.Cash;
  ngOnInit(): void { this.store.list(); }
}
