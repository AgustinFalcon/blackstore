import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { API_BASE } from '../../core/api';
import { BaseResponse } from '../../core/models/base-response';

@Component({
  selector: 'bs-sale-ticket',
  standalone: true,
  imports: [FormsModule],
  template: `
    <section class="page">
      <h2>Ticket</h2>
      <p class="lede">La reserva usa el simulador local. No hay llamada HTTP a StoreCore.</p>
      <div class="card">
        <form (ngSubmit)="reserve()">
          <label>Sesión <input name="sessionId" type="number" [(ngModel)]="cashSessionId" required /></label>
          <label>SKU <input name="sku" class="sku" [(ngModel)]="sku" required /></label>
          <label>Producto <input name="productName" [(ngModel)]="productName" required /></label>
          <label>Precio <input name="price" type="number" class="money" [(ngModel)]="originalUnitPrice" min="0" required /></label>
          <label>Descuento <input name="discount" type="number" class="money" [(ngModel)]="discountAmount" min="0" required /></label>
          <label>Importe efectivo
            <input name="amount" type="number" class="money" [(ngModel)]="amount" min="0.01" required />
          </label>
          <label>Comisión <input name="fee" type="number" class="money" [(ngModel)]="fee" min="0" required /></label>
          <label>Segundo medio <input name="secondMethod" [(ngModel)]="secondMethod" /></label>
          <label>Segundo importe <input name="secondAmount" type="number" class="money" [(ngModel)]="secondAmount" min="0" /></label>
          <button type="submit">Reservar y cobrar</button>
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
            <button type="button" class="ghost" (click)="refresh()">Consultar estado</button>
            <button type="button" class="primary" (click)="commit()">Confirmar venta</button>
            <button type="button" class="ghost" (click)="release()">Liberar reserva</button>
          </p>
        </div>
      }
      @if (paymentId(); as id) {
        <div class="card">
          <form (ngSubmit)="reverse(id)">
            <label>Motivo <input name="reversalReason" [(ngModel)]="reversalReason" required /></label>
            <button type="submit">Reversar pago {{ id }}</button>
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
export class SaleTicketComponent {
  private readonly http = inject(HttpClient);
  cashSessionId = 1;
  sku = 'SKU-1';
  productName = 'producto';
  originalUnitPrice = 20;
  discountAmount = 2;
  amount = 18;
  fee = 0.5;
  secondMethod = 'CARD';
  secondAmount = 0;
  reversalReason = 'devolucion';
  readonly keys = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '0'] as const;
  private actorId = 1;
  private operationId = '';
  readonly operationRef = signal<string | null>(null);
  readonly message = signal<string | null>(null);
  readonly paymentId = signal<number | null>(null);

  constructor() {
    this.http.get<BaseResponse<{ cashierId: number }>>(`${API_BASE}/workspace`).subscribe({
      next: (response) => {
        if (response.data) this.actorId = response.data.cashierId;
      },
    });
  }

  appendAmount(key: string): void {
    const current = String(this.amount ?? '');
    this.amount = Number((current === '0' ? key : current + key));
  }

  clearAmount(): void {
    this.amount = 0;
  }

  reserve(): void {
    const operationId = crypto.randomUUID();
    this.operationId = operationId;
    this.operationRef.set(operationId);
    this.paymentId.set(null);
    this.http
      .post<BaseResponse<{ status: string; receipt: string | null }>>(`${API_BASE}/sales/reservations`, {
        clientInstanceId: '11111111-1111-1111-1111-111111111111',
        deviceId: 'terminal-1',
        saleId: crypto.randomUUID(),
        operationId,
        cashSessionId: Number(this.cashSessionId),
        variantId: 'variant-1',
        quantity: 1,
        expectedPriceVersion: 'price-v1',
        sku: this.sku,
        productName: this.productName,
        originalUnitPrice: Number(this.originalUnitPrice),
        discountAmount: Number(this.discountAmount),
      })
      .subscribe({
        next: (reserve) => {
          this.http
            .post<BaseResponse<{ id: number; status: string; amount: number }>>(`${API_BASE}/payments`, {
              operationId,
              method: 'CASH',
              amount: Number(this.amount),
              feeAmount: Number(this.fee),
            })
            .subscribe({
              next: (payment) => {
                if (payment.data?.id) this.paymentId.set(payment.data.id);
                const extra = Number(this.secondAmount);
                if (extra > 0) {
                  this.http
                    .post<BaseResponse<{ status: string }>>(`${API_BASE}/payments`, {
                      operationId,
                      method: this.secondMethod || 'CARD',
                      amount: extra,
                      feeAmount: 0,
                    })
                    .subscribe({
                      next: (split) =>
                        this.message.set(
                          `Reserva ${reserve.data?.status} ${reserve.data?.receipt}. Pago ${payment.data?.status} y ${split.data?.status}.`,
                        ),
                      error: (err: HttpErrorResponse) => this.message.set(err.error?.errorCode ?? err.error?.message ?? 'No se pudo dividir el pago'),
                    });
                  return;
                }
                this.message.set(`Reserva ${reserve.data?.status} ${reserve.data?.receipt}. Pago ${payment.data?.status}.`);
              },
              error: (err: HttpErrorResponse) => this.message.set(err.error?.errorCode ?? err.error?.message ?? 'No se pudo cobrar'),
            });
        },
        error: (err: HttpErrorResponse) => this.message.set(err.error?.errorCode ?? err.error?.message ?? 'No se pudo reservar'),
      });
  }

  refresh(): void {
    if (!this.operationId) return;
    this.http.get<BaseResponse<{ status: string; operationId: string }>>(`${API_BASE}/sales/${this.operationId}`).subscribe({
      next: (response) => this.message.set(`GET ${response.data?.status} ${response.data?.operationId}`),
      error: (err: HttpErrorResponse) => this.message.set(err.error?.errorCode ?? err.error?.message ?? 'No se pudo consultar'),
    });
  }

  commit(): void {
    this.finish('commit');
  }

  release(): void {
    this.finish('release');
  }

  private finish(action: 'commit' | 'release'): void {
    this.http
      .post<BaseResponse<{ status: string }>>(`${API_BASE}/sales/${this.operationId}/${action}`, {})
      .subscribe({
        next: (response) => this.message.set(action === 'commit' ? `Venta ${response.data?.status}` : `Reserva ${response.data?.status}`),
        error: (err: HttpErrorResponse) => this.message.set(err.error?.errorCode ?? err.error?.message ?? 'No se pudo cerrar la saga'),
      });
  }

  reverse(paymentId: number): void {
    this.http
      .post<BaseResponse<{ status: string }>>(`${API_BASE}/payments/${paymentId}/reversals`, {
        operationId: this.operationId,
        reason: this.reversalReason,
        evidenceRef: `rev-${paymentId}`,
      }, {
        headers: {
          'X-Actor-Id': String(this.actorId),
          'X-Role': 'CASHIER',
          'X-Trace-Id': crypto.randomUUID(),
        },
      })
      .subscribe({
        next: (response) => this.message.set(`Reversa ${response.data?.status}. El pago original queda capturado.`),
        error: (err: HttpErrorResponse) => this.message.set(err.error?.errorCode ?? err.error?.message ?? 'No se pudo reversar'),
      });
  }
}
