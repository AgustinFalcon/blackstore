import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { API_BASE } from '../../core/api';
import { PaymentMethod, SaleAction, StaffRole } from '../../core/domain/pos-types';
import { PosWireMapper } from '../../core/infrastructure/pos-wire-mapper';
import { BaseResponse } from '../../core/models/base-response';
import { CounterContextService } from '../../core/services/counter-context.service';

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
  private operationId = '';
  readonly operationRef = signal<string | null>(null);
  readonly message = signal<string | null>(null);
  readonly paymentId = signal<number | null>(null);
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
    return this.blockReason() !== null;
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
    if (this.saleBlocked()) return;
    const operationId = crypto.randomUUID();
    this.operationId = operationId;
    this.operationRef.set(operationId);
    this.paymentId.set(null);
    this.http
      .post<BaseResponse<{ status: unknown; receipt: string | null }>>(`${API_BASE}/sales/reservations`, {
        clientInstanceId: '11111111-1111-1111-1111-111111111111',
        deviceId: 'terminal-1',
        saleId: crypto.randomUUID(),
        operationId,
        cashSessionId: Number(this.counter.openSession()?.id),
        variantId: this.variantId,
        quantity: 1,
        expectedPriceVersion: this.priceVersion,
        sku: this.sku,
        productName: this.productName,
        originalUnitPrice: Number(this.originalUnitPrice),
        discountAmount: Number(this.discountAmount),
      })
      .subscribe({
        next: (reserve) => {
          this.http
            .post<BaseResponse<{ id: number; status: unknown; amount: number }>>(`${API_BASE}/payments`, {
              operationId,
              method: PaymentMethod.Cash.wire,
              amount: Number(this.amount),
              feeAmount: Number(this.fee),
            })
            .subscribe({
              next: (payment) => {
                if (payment.data?.id) this.paymentId.set(payment.data.id);
                const extra = Number(this.secondAmount);
                if (extra > 0) {
                  this.http
                    .post<BaseResponse<{ status: unknown }>>(`${API_BASE}/payments`, {
                      operationId,
                      method: this.secondMethod.wire,
                      amount: extra,
                      feeAmount: 0,
                    })
                    .subscribe({
                      next: (split) => {
                        const saleStatus = PosWireMapper.saleStatus(reserve.data);
                        const paymentStatus = PosWireMapper.paymentStatus(payment.data);
                        const splitStatus = PosWireMapper.paymentStatus(split.data);
                        this.message.set(
                          `Reserva ${saleStatus.label} ${reserve.data?.receipt ?? ''}. Pago ${paymentStatus.label} y ${splitStatus.label}.`,
                        );
                      },
                      error: (err: HttpErrorResponse) => this.message.set(err.error?.errorCode ?? err.error?.message ?? 'No se pudo dividir el pago'),
                    });
                  return;
                }
                const saleStatus = PosWireMapper.saleStatus(reserve.data);
                const paymentStatus = PosWireMapper.paymentStatus(payment.data);
                this.message.set(`Reserva ${saleStatus.label} ${reserve.data?.receipt ?? ''}. Pago ${paymentStatus.label}.`);
              },
              error: (err: HttpErrorResponse) => this.message.set(err.error?.errorCode ?? err.error?.message ?? 'No se pudo cobrar'),
            });
        },
        error: (err: HttpErrorResponse) => this.message.set(err.error?.errorCode ?? err.error?.message ?? 'No se pudo reservar'),
      });
  }

  refresh(): void {
    if (!this.operationId) return;
    this.http.get<BaseResponse<{ status: unknown; operationId: string }>>(`${API_BASE}/sales/${this.operationId}`).subscribe({
      next: (response) => this.message.set(`GET ${PosWireMapper.saleStatus(response.data).label} ${response.data?.operationId ?? ''}`),
      error: (err: HttpErrorResponse) => this.message.set(err.error?.errorCode ?? err.error?.message ?? 'No se pudo consultar'),
    });
  }

  commit(): void {
    this.finish(SaleAction.Commit);
  }

  release(): void {
    this.finish(SaleAction.Release);
  }

  private finish(action: SaleAction): void {
    this.http
      .post<BaseResponse<{ status: unknown }>>(`${API_BASE}/sales/${this.operationId}/${action.wire}`, {})
      .subscribe({
        next: (response) => this.message.set(`${action.resultSubject} ${PosWireMapper.saleStatus(response.data).label}`),
        error: (err: HttpErrorResponse) => this.message.set(err.error?.errorCode ?? err.error?.message ?? 'No se pudo cerrar la saga'),
      });
  }

  reverse(paymentId: number): void {
    this.http
      .post<BaseResponse<{ status: unknown }>>(`${API_BASE}/payments/${paymentId}/reversals`, {
        operationId: this.operationId,
        reason: this.reversalReason,
        evidenceRef: `rev-${paymentId}`,
      }, {
        headers: {
          'X-Actor-Id': String(this.counter.cashierId()),
          'X-Role': StaffRole.Cashier.wire,
          'X-Trace-Id': crypto.randomUUID(),
        },
      })
      .subscribe({
        next: (response) => this.message.set(`Reversa ${PosWireMapper.paymentStatus(response.data).label}. El pago original queda capturado.`),
        error: (err: HttpErrorResponse) => this.message.set(err.error?.errorCode ?? err.error?.message ?? 'No se pudo reversar'),
      });
  }
}
