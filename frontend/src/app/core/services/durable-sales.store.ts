import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, concatMap, map, throwError } from 'rxjs';
import { API_BASE } from '../api';
import { AllowedAction, DurableSaleDetail, DurableSaleState, DurableSaleSummary } from '../domain/durable-sale';
import { PaymentMethod, PaymentStatus } from '../domain/pos-types';
import { StaffPermission } from '../domain/session-types';
import { PaymentAttempt, TicketMoney, sameTicketIdentity } from '../domain/ticket-transition';
import { PosWireMapper } from '../infrastructure/pos-wire-mapper';
import { SessionStore } from './session.store';

/** Read-only entry point: opening an existing identity cannot reserve or capture. */
export class OpenExistingSale {
  constructor(private readonly read: (operationId: string) => Observable<DurableSaleDetail>) {}
  execute(operationId: string): Observable<DurableSaleDetail> { return this.read(operationId); }
}

@Injectable({ providedIn: 'root' })
export class DurableSalesStore {
  private readonly http = inject(HttpClient);
  private readonly session = inject(SessionStore);
  private generation = 0;
  private listGeneration = 0;
  private detailGeneration = 0;
  readonly items = signal<readonly DurableSaleSummary[]>([]);
  readonly nextCursor = signal<string | null>(null);
  readonly detail = signal<DurableSaleDetail | null>(null);
  readonly loadingList = signal(false);
  readonly busy = signal(false);
  readonly notice = signal<string | null>(null);
  private readonly opening = new OpenExistingSale(operationId => this.read(operationId));

  constructor() {
    this.session.changed.subscribe(() => {
      ++this.generation; ++this.listGeneration; ++this.detailGeneration;
      this.items.set([]); this.detail.set(null); this.nextCursor.set(null);
      this.loadingList.set(false); this.busy.set(false); this.notice.set(null);
    });
  }

  list(cursor: string | null = null, state: DurableSaleState | null = null): void {
    if (!this.session.can(StaffPermission.SaleRead)) return;
    const generation = this.generation;
    const request = ++this.listGeneration;
    this.loadingList.set(true); this.notice.set(null);
    let params = new HttpParams().set('limit', 20);
    if (cursor) params = params.set('cursor', cursor);
    if (state && state !== DurableSaleState.Unknown) params = params.set('state', state.wire);
    this.http.get<unknown>(`${API_BASE}/sales`, { params }).subscribe({
      next: response => {
        if (generation !== this.generation || request !== this.listGeneration) return;
        const page = PosWireMapper.durablePage(response);
        this.items.set(page?.items ?? []); this.nextCursor.set(page?.nextCursor ?? null); this.loadingList.set(false);
        if (!page) this.notice.set('No se pudo comprobar la lista. Reintentá la consulta.');
      },
      error: () => {
        if (generation !== this.generation || request !== this.listGeneration) return;
        this.items.set([]); this.nextCursor.set(null); this.loadingList.set(false);
        this.notice.set('Ventas no disponibles. Reintentá la consulta.');
      },
    });
  }

  open(operationId: string): void {
    if (!this.session.can(StaffPermission.SaleRead) || this.busy()) return;
    const generation = this.generation;
    const request = ++this.detailGeneration;
    this.detail.set(null); this.notice.set(null); this.busy.set(true);
    this.opening.execute(operationId).subscribe({
      next: detail => { if (generation === this.generation && request === this.detailGeneration) { this.detail.set(detail); this.busy.set(false); } },
      error: () => { if (generation === this.generation && request === this.detailGeneration) { this.busy.set(false); this.notice.set('No se pudo comprobar la venta o no tenés acceso. Reintentá la consulta.'); } },
    });
  }

  close(): void { if (!this.busy()) { ++this.detailGeneration; this.detail.set(null); this.notice.set(null); } }
  can(action: AllowedAction): boolean {
    const detail = this.detail();
    return !this.busy() && !!detail && this.session.can(action.permission) && detail.status.permits(action, detail);
  }

  execute(action: AllowedAction, reason: string, amount?: unknown, fee?: unknown, method = PaymentMethod.Cash, paymentId?: number): void {
    const original = this.detail();
    if (!original || !this.can(action)) return;
    const requestedAmount = TicketMoney.fromDecimal(amount);
    const requestedFee = TicketMoney.fromDecimal(fee);
    if (action === AllowedAction.CapturePayment && (!requestedAmount || requestedAmount.cents <= 0n || !requestedFee || requestedFee.cents < 0n || method === PaymentMethod.Unknown)) return;
    if (action === AllowedAction.ReversePayment && (!reason.trim() || !original.payments.some(payment => payment.paymentId === paymentId && payment.status === PaymentStatus.Captured))) return;
    const generation = this.generation;
    const request = ++this.detailGeneration;
    this.busy.set(true); this.detail.set(null); this.notice.set(null);
    const active = () => generation === this.generation && request === this.detailGeneration && this.session.can(action.permission);
    this.read(original.identity.operationId).pipe(concatMap(current => {
      if (!active() || !sameTicketIdentity(current.identity, original.identity) || !current.status.permits(action, current)) return throwError(() => new Error('Acción no disponible'));
      let mutation: Observable<unknown>;
      if (action === AllowedAction.CapturePayment) {
        if (!requestedAmount || !requestedFee || !current.pending || requestedAmount.cents > current.pending.cents) return throwError(() => new Error('Importe inválido'));
        const attempt: PaymentAttempt = { identity: current.identity, amount: requestedAmount, fee: requestedFee, method };
        mutation = this.http.post<unknown>(`${API_BASE}/payments`, { ...current.identity, amount: requestedAmount.decimal, feeAmount: requestedFee.decimal, method: method.wire, reason }).pipe(map(response => {
          if (!PosWireMapper.capturedPayment(response, attempt)) throw new Error('No se pudo comprobar el pago');
          return response;
        }));
      } else if (action === AllowedAction.ReversePayment) {
        if (!current.payments.some(payment => payment.paymentId === paymentId && payment.status === PaymentStatus.Captured)) return throwError(() => new Error('Pago no disponible'));
        mutation = this.http.post<unknown>(`${API_BASE}/payments/${paymentId}/reversals`, { ...current.identity, reason, evidenceRef: `rev-${paymentId}` });
      } else {
        const route = action === AllowedAction.Commit ? 'commit' : 'release';
        mutation = this.http.post<unknown>(`${API_BASE}/sales/${encodeURIComponent(current.identity.operationId)}/${route}`, { ...current.identity, reason });
      }
      return mutation.pipe(concatMap(() => active() ? this.read(current.identity.operationId) : throwError(() => new Error('Sesión cambió'))));
    })).subscribe({
      next: detail => { if (active()) { this.detail.set(detail); this.busy.set(false); } },
      error: () => { if (generation === this.generation && request === this.detailGeneration) { this.busy.set(false); this.notice.set('No se pudo confirmar la operación. Consultá la venta antes de continuar.'); } },
    });
  }

  private read(operationId: string): Observable<DurableSaleDetail> {
    return this.http.get<unknown>(`${API_BASE}/sales/operations/${encodeURIComponent(operationId)}`).pipe(map(response => {
      const detail = PosWireMapper.durableDetail(response, operationId);
      if (!detail) throw new Error('Detalle inválido');
      return detail;
    }));
  }
}
