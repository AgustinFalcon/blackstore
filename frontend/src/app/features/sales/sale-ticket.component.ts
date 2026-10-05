import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { API_BASE } from '../../core/api';
import { PaymentMethod, SaleAction, StaffRole } from '../../core/domain/pos-types';
import { PosWireMapper } from '../../core/infrastructure/pos-wire-mapper';
import { BaseResponse } from '../../core/models/base-response';
import { CounterContextService } from '../../core/services/counter-context.service';
import { concatMap, finalize, throwError } from 'rxjs';
import { PaymentAttempt, TicketIdentity, TicketMoney, TicketSnapshot, TicketTransitionPolicy } from '../../core/domain/ticket-transition';
import { CapturePaymentStep, RefreshTicketStep, ReserveTicketStep, TicketAttemptContext, TicketFlowPort, TicketFlowResult, TicketPaymentJourney } from './ticket-steps';

@Component({
  selector: 'bs-sale-ticket',
  standalone: true,
  imports: [FormsModule],
  template: `
    <section class="page">
      <h2>Ticket</h2>
      <p class="lede">La reserva usa el simulador local y el catálogo proyectado. No hay llamada HTTP a StoreCore.</p>
      @if (blockReason(); as reason) {
        <p class="banner warn" role="status">{{ reason }}</p>
      }
      <div class="card">
        <h3>Línea</h3>
        <table>
          <thead>
            <tr>
              <th>SKU</th>
              <th>Nombre</th>
              <th>Original</th>
              <th>Descuento</th>
              <th>Effective</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td class="sku">{{ sku }}</td>
              <td>{{ productName }}</td>
              <td class="money">{{ originalUnitPrice }}</td>
              <td class="money">{{ discountAmount }}</td>
              <td class="money">{{ effectiveUnitPrice() }}</td>
            </tr>
          </tbody>
        </table>
        <p>Cobrado <span class="money">{{ amount }}</span> · comisiones <span class="money">{{ fee }}</span></p>
      </div>
      <div class="card">
        <form (ngSubmit)="reserve()">
          <p>
            Sesión
            @if (counter.openSession(); as session) {
              <span class="sku">{{ session.id }}</span>
            } @else {
              sin abrir
            }
          </p>
          <label>
            SKU del catálogo
            <select name="sku" [(ngModel)]="sku" (ngModelChange)="applySku($event)">
              @for (item of counter.catalog()?.items ?? []; track item.sku) {
                <option [value]="item.sku">{{ item.sku }} · {{ item.name }}</option>
              }
            </select>
          </label>
          <p>
            Variante <span class="sku">{{ variantId }}</span>
            · versión de precio <span class="sku">{{ priceVersion }}</span>.
            @if (priceVersion === 'price-v1') {
              La proyección fixture no trae effective: el precio lo carga el cajero.
            }
          </p>
          <label>Producto <input name="productName" [ngModel]="productName" readonly /></label>
          <label>Precio <input name="price" type="number" class="money" [(ngModel)]="originalUnitPrice" min="0" required /></label>
          <label>Descuento <input name="discount" type="number" class="money" [(ngModel)]="discountAmount" min="0" required /></label>
          <label>Importe efectivo
            <input name="amount" type="number" class="money" [(ngModel)]="amount" min="0.01" required />
          </label>
          <label>Comisión <input name="fee" type="number" class="money" [(ngModel)]="fee" min="0" required /></label>
          <label>
            Segundo medio
            <select name="secondMethod" [(ngModel)]="secondMethod">
              @for (method of paymentMethods; track method.wire) {
                <option [ngValue]="method">{{ method.label }}</option>
              }
            </select>
          </label>
          <label>Segundo importe <input name="secondAmount" type="number" class="money" [(ngModel)]="secondAmount" min="0" /></label>
          <button type="submit" [disabled]="saleBlocked()">Reservar y cobrar</button>
        </form>
        <div class="keypad" aria-label="Teclado numérico para importe">
          @for (key of keys; track key) {
            <button type="button" (click)="appendAmount(key)">{{ key }}</button>
          }
          <button type="button" (click)="clearAmount()">C</button>
        </div>
      </div>
      @if (message()) {
        <div class="card">
          <p>{{ message() }}</p>
          @if (operationRef()) {
            <p>Cuádruple operationId <span class="sku">{{ operationRef() }}</span> · GET local (no StoreCore)</p>
          }
          <p class="actions">
            <button type="button" class="ghost" (click)="refresh()" [disabled]="busy()">Consultar estado</button>
            <button type="button" class="primary" (click)="commit()" [disabled]="!canFinish(commitAction)">Confirmar venta</button>
            <button type="button" class="ghost" (click)="release()" [disabled]="!canFinish(releaseAction)">Liberar reserva</button>
          </p>
        </div>
      }
      @if (paymentId(); as id) {
        <div class="card">
          <form (ngSubmit)="reverse(id)">
            <label>Motivo <input name="reversalReason" [(ngModel)]="reversalReason" required /></label>
            <button type="submit" [disabled]="!canFinish(reverseAction)">Reversar pago {{ id }}</button>
          </form>
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
    `,
  ],
})
export class SaleTicketComponent implements OnInit {
  private readonly http = inject(HttpClient);
  readonly counter = inject(CounterContextService);
  sku = '';
  productName = '';
  variantId = '';
  priceVersion = 'price-v1';
  originalUnitPrice = 20;
  discountAmount = 2;
  amount = 18;
  fee = 0.5;
  readonly paymentMethods = PaymentMethod.selectable;
  secondMethod: PaymentMethod = PaymentMethod.Card;
  secondAmount = 0;
  reversalReason = 'devolucion';
  readonly keys = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '0'] as const;
  readonly busy = signal(false);
  readonly commitAction = SaleAction.Commit;
  readonly releaseAction = SaleAction.Release;
  readonly reverseAction = SaleAction.Reverse;
  private attempt: TicketAttemptContext | null = null;
  private readonly snapshot = signal<TicketSnapshot | null>(null);
  private readonly port: TicketFlowPort = {
    reserve: (body) => this.http.post<unknown>(`${API_BASE}/sales/reservations`, body),
    capture: (attempt) => this.http.post<unknown>(`${API_BASE}/payments`, {
      ...attempt.identity, method: attempt.method.wire, amount: attempt.amount.decimal, feeAmount: attempt.fee.decimal,
    }),
    refresh: (identity) => this.http.get<unknown>(`${API_BASE}/sales/${identity.operationId}`),
  };
  private readonly refreshStep = new RefreshTicketStep(this.port);
  readonly operationRef = signal<string | null>(null);
  readonly message = signal<string | null>(null);
  readonly paymentId = signal<number | null>(null);
  private readonly journey = new TicketPaymentJourney(
    new ReserveTicketStep(this.port),
    new CapturePaymentStep(this.port),
    this.refreshStep,
    (result) => this.recordJourneyProgress(result),
  );
  private readonly syncCatalogLine = effect(() => {
    const items = this.counter.catalog()?.items ?? [];
    const current = items.find((item) => item.sku === this.sku) ?? items[0];
    if (!current) return;
    this.applySku(current.sku);
  });

  ngOnInit(): void {
    this.counter.load();
  }

  blockReason(): string | null {
    return this.counter.blockReason();
  }

  saleBlocked(): boolean {
    return this.busy() || this.blockReason() !== null;
  }

  applySku(sku: string): void {
    const item = this.counter.catalog()?.items.find((row) => row.sku === sku);
    if (!item) return;
    this.sku = item.sku;
    this.productName = item.name;
    this.variantId = item.variantId;
    this.priceVersion = item.priceVersion?.trim() || 'price-v1';
    if (item.unitPrice != null) {
      this.originalUnitPrice = Number(item.unitPrice);
      this.amount = Math.max(0, this.originalUnitPrice - Number(this.discountAmount));
    }
  }

  effectiveUnitPrice(): number {
    return Math.max(0, Number(this.originalUnitPrice) - Number(this.discountAmount));
  }

  appendAmount(key: string): void {
    const current = String(this.amount ?? '');
    this.amount = Number((current === '0' ? key : current + key));
  }

  clearAmount(): void {
    this.amount = 0;
  }

  reserve(): void {
    if (!TicketTransitionPolicy.beginReserve(this.busy(), this.blockReason() === null).permitsWrite) return;
    // Acquire before UUID creation: synchronous double submit cannot create another identity.
    this.busy.set(true);
    const amount = TicketMoney.fromDecimal(this.amount);
    const fee = TicketMoney.fromDecimal(this.fee);
    const extra = TicketMoney.fromDecimal(this.secondAmount);
    const price = TicketMoney.fromDecimal(this.originalUnitPrice);
    const discount = TicketMoney.fromDecimal(this.discountAmount);
    const decision = TicketTransitionPolicy.reservationAmounts({ price, discount, amount, fee, extra, secondMethod: this.secondMethod });
    if (!decision.permitsWrite || !price || !discount || !amount || !fee || !extra) {
      this.message.set(decision.label);
      this.busy.set(false);
      return;
    }
    const identity: TicketIdentity = Object.freeze({
      clientInstanceId: '11111111-1111-1111-1111-111111111111',
      deviceId: 'terminal-1', saleId: crypto.randomUUID(), operationId: crypto.randomUUID(),
    });
    const payments: PaymentAttempt[] = [Object.freeze({ identity, method: PaymentMethod.Cash, amount, fee })];
    if (extra.cents > 0n) payments.push(Object.freeze({ identity, method: this.secondMethod, amount: extra, fee: TicketMoney.fromDecimal(0)! }));
    const context: TicketAttemptContext = Object.freeze({
      identity,
      reservation: Object.freeze({
        ...identity, cashSessionId: Number(this.counter.openSession()?.id), variantId: this.variantId,
        quantity: 1, expectedPriceVersion: this.priceVersion, sku: this.sku, productName: this.productName,
        originalUnitPrice: price.decimal, discountAmount: discount.decimal,
      }),
      payments: Object.freeze(payments),
    });
    this.attempt = context;
    this.operationRef.set(identity.operationId);
    this.snapshot.set(null);
    this.paymentId.set(null);
    this.journey.execute(context).pipe(finalize(() => { if (this.attempt === context) this.busy.set(false); })).subscribe({
      next: (result) => {
        if (this.attempt !== context) return;
        this.recordJourneyProgress(result);
      },
      error: (err: unknown) => { if (this.attempt === context) this.message.set(this.failureMessage(err)); },
    });
  }

  refresh(): void {
    const context = this.attempt;
    if (!context || this.busy()) return;
    this.busy.set(true);
    this.snapshot.set(null);
    this.refreshStep.execute(context.identity).pipe(finalize(() => { if (this.attempt === context) this.busy.set(false); })).subscribe({
      next: (snapshot) => {
        if (this.attempt !== context) return;
        this.snapshot.set(snapshot);
        this.message.set(`GET ${snapshot.status.label}. ${snapshot.coverage.label}.`);
      },
      error: (err: unknown) => { if (this.attempt === context) { this.snapshot.set(null); this.message.set(this.failureMessage(err)); } },
    });
  }

  commit(): void {
    this.finish(SaleAction.Commit);
  }

  release(): void {
    this.finish(SaleAction.Release);
  }

  private finish(action: SaleAction): void {
    const context = this.attempt;
    if (!context || !this.canFinish(action)) return;
    this.busy.set(true);
    this.snapshot.set(null);
    this.refreshStep.execute(context.identity).pipe(concatMap((snapshot) => {
      if (this.attempt !== context) return throwError(() => new Error('El intento ya no está activo.'));
      const decision = TicketTransitionPolicy.decide(snapshot, action);
      if (!decision.permitsRequest) return throwError(() => new Error(decision.label));
      return this.http.post<unknown>(`${API_BASE}/sales/${context.identity.operationId}/${action.wire}`, {});
    }), concatMap((response) => {
      const snapshot = PosWireMapper.ticket(response, context.identity);
      if (!snapshot.evidenceValid) return throwError(() => new Error('Respuesta de venta no válida. Consultá el estado.'));
      this.snapshot.set(snapshot);
      this.message.set(`${action.resultSubject} ${snapshot.status.label}`);
      return this.refreshStep.execute(context.identity);
    }), finalize(() => { if (this.attempt === context) this.busy.set(false); })).subscribe({
      next: (snapshot) => { if (this.attempt === context) this.snapshot.set(snapshot); },
      error: (err: unknown) => { if (this.attempt === context) { this.snapshot.set(null); this.message.set(this.failureMessage(err)); } },
    });
  }

  canFinish(action: SaleAction): boolean {
    const decision = TicketTransitionPolicy.decide(this.snapshot(), action);
    return !this.busy() && (action === SaleAction.Reverse ? decision.permitsWrite : decision.permitsRequest);
  }

  private recordJourneyProgress(result: TicketFlowResult): void {
    this.snapshot.set(result.snapshot);
    this.paymentId.set(result.payments[0]?.paymentId ?? this.paymentId());
    this.message.set(`Reserva ${result.snapshot.status.label}. ${result.snapshot.coverage.label}.`);
  }

  private failureMessage(error: unknown): string {
    return error instanceof Error && !(error instanceof HttpErrorResponse) ? error.message : 'No se pudo comprobar la operación. Consultá su estado.';
  }

  reverse(paymentId: number): void {
    const context = this.attempt;
    if (!context || !this.canFinish(SaleAction.Reverse) || paymentId !== this.paymentId()) return;
    this.busy.set(true);
    this.snapshot.set(null);
    this.refreshStep.execute(context.identity).pipe(concatMap((snapshot) => {
      const decision = TicketTransitionPolicy.decide(snapshot, SaleAction.Reverse);
      if (this.attempt !== context || !decision.permitsWrite) return throwError(() => new Error(decision.label));
      return this.http.post<BaseResponse<{ status: unknown }>>(`${API_BASE}/payments/${paymentId}/reversals`, {
        ...context.identity,
        reason: this.reversalReason,
        evidenceRef: `rev-${paymentId}`,
      }, {
        headers: {
          'X-Actor-Id': String(this.counter.cashierId()),
          'X-Role': StaffRole.Cashier.wire,
          'X-Trace-Id': crypto.randomUUID(),
        },
      });
    }), finalize(() => { if (this.attempt === context) this.busy.set(false); })).subscribe({
        next: (response) => {
          if (this.attempt !== context) return;
          this.paymentId.set(null);
          this.message.set(`Reversa ${PosWireMapper.paymentStatus(response.data).label}. Consultá la venta antes de continuar.`);
        },
        error: (err: unknown) => { if (this.attempt === context) this.message.set(this.failureMessage(err)); },
      });
  }
}
