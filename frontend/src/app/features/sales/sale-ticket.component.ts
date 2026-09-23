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
    <section>
      <h2>Ticket</h2>
      <p>La reserva usa el simulador local. No hay llamada HTTP a StoreCore.</p>
      <form (ngSubmit)="reserve()">
        <label>Sesión <input name="sessionId" type="number" [(ngModel)]="cashSessionId" required /></label>
        <label>SKU <input name="sku" [(ngModel)]="sku" required /></label>
        <label>Producto <input name="productName" [(ngModel)]="productName" required /></label>
        <label>Precio <input name="price" type="number" [(ngModel)]="originalUnitPrice" min="0" required /></label>
        <label>Descuento <input name="discount" type="number" [(ngModel)]="discountAmount" min="0" required /></label>
        <label>Importe <input name="amount" type="number" [(ngModel)]="amount" min="0.01" required /></label>
        <label>Comisión <input name="fee" type="number" [(ngModel)]="fee" min="0" required /></label>
        <label>Segundo medio <input name="secondMethod" [(ngModel)]="secondMethod" /></label>
        <label>Segundo importe <input name="secondAmount" type="number" [(ngModel)]="secondAmount" min="0" /></label>
        <button type="submit">Reservar y cobrar</button>
      </form>
      @if (message()) {
        <p>{{ message() }}</p>
        <button type="button" (click)="commit()">Confirmar venta</button>
        <button type="button" (click)="release()">Liberar reserva</button>
      }
      @if (paymentId(); as id) {
        <form (ngSubmit)="reverse(id)">
          <label>Motivo <input name="reversalReason" [(ngModel)]="reversalReason" required /></label>
          <button type="submit">Reversar pago {{ id }}</button>
        </form>
      }
    </section>
  `,
  styles: [
    `
      form { display: flex; gap: 1rem; align-items: end; flex-wrap: wrap; }
      label { display: flex; flex-direction: column; gap: 0.25rem; }
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
  private actorId = 1;
  private operationId = '';
  readonly message = signal<string | null>(null);
  readonly paymentId = signal<number | null>(null);

  constructor() {
    this.http.get<BaseResponse<{ cashierId: number }>>(`${API_BASE}/workspace`).subscribe({
      next: (response) => {
        if (response.data) this.actorId = response.data.cashierId;
      },
    });
  }

  reserve(): void {
    const operationId = crypto.randomUUID();
    this.operationId = operationId;
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
                      error: (err: HttpErrorResponse) => this.message.set(err.error?.message ?? 'No se pudo dividir el pago'),
                    });
                  return;
                }
                this.message.set(`Reserva ${reserve.data?.status} ${reserve.data?.receipt}. Pago ${payment.data?.status}.`);
              },
              error: (err: HttpErrorResponse) => this.message.set(err.error?.message ?? 'No se pudo cobrar'),
            });
        },
        error: (err: HttpErrorResponse) => this.message.set(err.error?.message ?? 'No se pudo reservar'),
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
        error: (err: HttpErrorResponse) => this.message.set(err.error?.message ?? 'No se pudo cerrar la saga'),
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
        error: (err: HttpErrorResponse) => this.message.set(err.error?.message ?? 'No se pudo reversar'),
      });
  }
}
