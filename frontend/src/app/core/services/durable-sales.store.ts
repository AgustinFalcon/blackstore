import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, concatMap, map, throwError } from 'rxjs';
import { API_BASE } from '../api';
import { AllowedAction, DurableSaleDetail, DurableSaleState, DurableSaleSummary } from '../domain/durable-sale';
import { PaymentMethod, PaymentStatus } from '../domain/pos-types';
import { StaffPermission } from '../domain/session-types';
import { PaymentAttempt, TicketMoney, sameTicketIdentity } from '../domain/ticket-transition';
import { PosWireMapper } from '../infrastructure/pos-wire-mapper';
import { AccountingCommand, AccountingCommandKind, CommandOutcome } from '../domain/accounting-command';
import { AccountingCommandsStore } from './accounting-commands.store';
import { SessionStore } from './session.store';
import { SaleCommandsStore } from './sale-commands.store';
import { SaleCommand, SaleCommandKind, SaleAdmissionOutcome } from '../domain/sale-command';

/** Read-only entry point: opening an existing identity cannot reserve or capture. */
export class OpenExistingSale {
  constructor(private readonly read: (operationId: string) => Observable<DurableSaleDetail>) {}
  execute(operationId: string): Observable<DurableSaleDetail> { return this.read(operationId); }
}

@Injectable({ providedIn: 'root' })
export class DurableSalesStore {
  private readonly http = inject(HttpClient);
  readonly commands = inject(AccountingCommandsStore);
  readonly sales = inject(SaleCommandsStore);
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
    return !this.busy() && !this.commands.unresolved() && !this.commands.blocked().blocksWrites && !!detail && this.session.can(action.permission) && detail.status.permits(action, detail);
  }

  execute(action: AllowedAction, reason: string, amount?: unknown, method = PaymentMethod.Cash, paymentId?: number): void {
    const original = this.detail();
    if (!original || !this.can(action)) return;
    const requestedAmount = TicketMoney.fromDecimal(amount);
    if (action === AllowedAction.CapturePayment && (!requestedAmount || requestedAmount.cents <= 0n || method === PaymentMethod.Unknown)) return;
    if (action === AllowedAction.ReversePayment && (!reason.trim() || !original.payments.some(payment => payment.paymentId === paymentId && payment.reversibility.permitsReverse))) return;
    const generation = this.generation;
    const request = ++this.detailGeneration;
    this.busy.set(true); this.detail.set(null); this.notice.set(null);
    const active = () => generation === this.generation && request === this.detailGeneration && this.session.can(action.permission);
    this.read(original.identity.operationId).pipe(concatMap(current => {
      if (!active() || !sameTicketIdentity(current.identity, original.identity) || !current.status.permits(action, current)) return throwError(() => new Error('Acción no disponible'));
      let mutation: Observable<unknown>;
      if (action === AllowedAction.CapturePayment) {
        if (!requestedAmount || !current.pending || requestedAmount.cents > current.pending.cents) return throwError(() => new Error('Importe inválido'));
        mutation = this.commands.execute(AccountingCommand.create(AccountingCommandKind.Capture, {
          ...current.identity, amount: requestedAmount.decimal, paymentMethod: method.wire, reason,
        })).pipe(map(receipt => {
          if (receipt.outcome !== CommandOutcome.Committed || !receipt.paymentId) throw new Error(receipt.failure.label || receipt.outcome.label);
          return receipt;
        }));
      } else if (action === AllowedAction.ReversePayment) {
        if (!current.payments.some(payment => payment.paymentId === paymentId && payment.reversibility.permitsReverse)) return throwError(() => new Error('Pago no disponible'));
        mutation = this.commands.execute(AccountingCommand.create(AccountingCommandKind.Reverse, {
          ...current.identity, originalPaymentId: paymentId, reason, evidenceRef: `rev-${paymentId}`,
        }, paymentId)).pipe(map(receipt => {
          if (receipt.outcome !== CommandOutcome.Committed) throw new Error(receipt.failure.label || receipt.outcome.label);
          return receipt;
        }));
      } else {
        const kind = action === AllowedAction.Commit ? SaleCommandKind.Commit : SaleCommandKind.Release;
        mutation = this.sales.execute(SaleCommand.create(kind,current.identity,current.cashSessionId,{ reason })).pipe(map(result => {
          if (result.outcome !== SaleAdmissionOutcome.Accepted) throw new Error(result.outcome.label);
          return result;
        }));
      }
      return mutation.pipe(concatMap(() => active() ? this.read(current.identity.operationId) : throwError(() => new Error('Sesión cambió'))));
    })).subscribe({
      next: detail => { if (active()) { this.detail.set(detail); this.busy.set(false); } },
      error: () => { if (generation === this.generation && request === this.detailGeneration) { this.busy.set(false); this.notice.set('No se pudo confirmar la operación. Consultá la venta antes de continuar.'); } },
    });
  }

  consultReceipt(): void {
    if (this.busy()) return;
    const generation = this.generation;
    this.busy.set(true);
    if (this.sales.unresolved()) {
      this.sales.consult().subscribe(result => {
        if (generation !== this.generation) return;
        this.busy.set(false); this.detail.set(null); this.notice.set(result.outcome.label);
      });
      return;
    }
    this.commands.consult().subscribe(receipt => {
      if (generation !== this.generation) return;
      this.busy.set(false); this.detail.set(null);
      this.notice.set(receipt.failure.label || receipt.outcome.label);
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
