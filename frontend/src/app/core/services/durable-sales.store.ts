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
import { ReversalReason } from '../domain/reversal-reason';
import { SurfaceDataSource } from '../domain/data-provenance';
import { OperationId, SaleSelectionPhase } from '../domain/sale-operation-selection';

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
  private commandGeneration = 0;
  private detailPublicationGeneration = 0;
  private openPublication: number | null = null;
  readonly items = signal<readonly DurableSaleSummary[]>([]);
  readonly nextCursor = signal<string | null>(null);
  readonly detail = signal<DurableSaleDetail | null>(null);
  readonly loadingList = signal(false);
  readonly listSource = signal(SurfaceDataSource.Unknown);
  readonly detailSource = signal(SurfaceDataSource.Unknown);
  readonly selectedOperationId = signal<OperationId | null>(null);
  readonly selectionPhase = signal(SaleSelectionPhase.Idle);
  readonly busy = signal(false);
  readonly notice = signal<string | null>(null);
  private readonly opening = new OpenExistingSale(operationId => this.read(operationId));

  constructor() {
    this.session.changed.subscribe(() => {
      ++this.generation; ++this.listGeneration; ++this.commandGeneration; ++this.detailPublicationGeneration;
      this.openPublication = null;
      this.items.set([]); this.detail.set(null); this.nextCursor.set(null);
      this.loadingList.set(false); this.busy.set(false); this.notice.set(null);
      this.listSource.set(SurfaceDataSource.Unknown); this.detailSource.set(SurfaceDataSource.Unknown);
      this.selectedOperationId.set(null); this.selectionPhase.set(SaleSelectionPhase.Idle);
    });
  }

  list(cursor: string | null = null, state: DurableSaleState | null = null): void {
    if (!this.session.can(StaffPermission.SaleRead)) return;
    const generation = this.generation;
    const request = ++this.listGeneration;
    this.loadingList.set(true); this.notice.set(null); this.listSource.set(SurfaceDataSource.Unknown);
    let params = new HttpParams().set('limit', 20);
    if (cursor) params = params.set('cursor', cursor);
    if (state && state !== DurableSaleState.Unknown) params = params.set('state', state.wire);
    this.http.get<unknown>(`${API_BASE}/sales`, { params }).subscribe({
      next: response => {
        if (generation !== this.generation || request !== this.listGeneration) return;
        const page = PosWireMapper.durablePage(response);
        this.items.set(page?.items ?? []); this.nextCursor.set(page?.nextCursor ?? null); this.loadingList.set(false);
        this.listSource.set(page ? SurfaceDataSource.Real : SurfaceDataSource.Unavailable);
      },
      error: () => {
        if (generation !== this.generation || request !== this.listGeneration) return;
        this.items.set([]); this.nextCursor.set(null); this.loadingList.set(false);
        this.listSource.set(SurfaceDataSource.Unavailable);
      },
    });
  }

  open(operationId: string): void {
    if (!this.session.can(StaffPermission.SaleRead) || this.busy()) return;
    const generation = this.generation;
    const publication = ++this.detailPublicationGeneration;
    this.openPublication = publication;
    this.selectedOperationId.set(null); this.selectionPhase.set(SaleSelectionPhase.Idle);
    this.detail.set(null); this.notice.set(null); this.busy.set(true); this.detailSource.set(SurfaceDataSource.Unknown);
    this.opening.execute(operationId).subscribe({
      next: detail => { if (generation === this.generation && publication === this.detailPublicationGeneration) {
        this.openPublication = null; this.detail.set(detail); this.detailSource.set(SurfaceDataSource.Real); this.busy.set(false);
      } },
      error: () => { if (generation === this.generation && publication === this.detailPublicationGeneration) {
        this.openPublication = null; this.busy.set(false); this.detailSource.set(SurfaceDataSource.Unavailable); this.notice.set('No se pudo comprobar la venta o no tenés acceso. Reintentá la consulta.');
      } },
    });
  }

  selectOperation(operationId: OperationId): void {
    if (!this.session.can(StaffPermission.SaleRead)) return;
    const generation = this.generation;
    const publication = ++this.detailPublicationGeneration;
    if (this.openPublication !== null) { this.openPublication = null; this.busy.set(false); }
    this.selectedOperationId.set(operationId);
    this.selectionPhase.set(SaleSelectionPhase.Loading);
    this.detail.set(null); this.notice.set(null); this.detailSource.set(SurfaceDataSource.Unknown);
    this.opening.execute(operationId.wire).subscribe({
      next: detail => {
        if (generation !== this.generation || publication !== this.detailPublicationGeneration || !operationId.equals(this.selectedOperationId())) return;
        if (!operationId.equals(OperationId.fromWire(detail.identity.operationId))) {
          this.detail.set(null); this.detailSource.set(SurfaceDataSource.Unavailable); this.selectionPhase.set(SaleSelectionPhase.Unavailable); return;
        }
        this.detail.set(detail); this.detailSource.set(SurfaceDataSource.Real); this.selectionPhase.set(SaleSelectionPhase.Ready);
      },
      error: () => {
        if (generation !== this.generation || publication !== this.detailPublicationGeneration || !operationId.equals(this.selectedOperationId())) return;
        this.detail.set(null); this.detailSource.set(SurfaceDataSource.Unavailable); this.selectionPhase.set(SaleSelectionPhase.Unavailable);
      },
    });
  }

  retrySelection(): void {
    const operationId = this.selectedOperationId();
    if (operationId && this.selectionPhase().canRetry) this.selectOperation(operationId);
  }

  refreshSelection(): void {
    const operationId = this.selectedOperationId();
    if (operationId && this.selectionPhase() === SaleSelectionPhase.Ready) this.selectOperation(operationId);
  }

  rejectSelection(): void {
    ++this.detailPublicationGeneration;
    if (this.openPublication !== null) { this.openPublication = null; this.busy.set(false); }
    this.selectedOperationId.set(null); this.detail.set(null); this.detailSource.set(SurfaceDataSource.Unknown);
    this.selectionPhase.set(SaleSelectionPhase.Unknown);
  }

  clearSelection(): void {
    ++this.detailPublicationGeneration;
    if (this.openPublication !== null) { this.openPublication = null; this.busy.set(false); }
    this.selectedOperationId.set(null); this.detail.set(null); this.detailSource.set(SurfaceDataSource.Unknown);
    this.selectionPhase.set(SaleSelectionPhase.Idle);
  }

  leaveSelection(): void {
    if (this.selectedOperationId() === null && this.selectionPhase() === SaleSelectionPhase.Idle) return;
    ++this.detailPublicationGeneration;
    this.selectedOperationId.set(null); this.detail.set(null); this.detailSource.set(SurfaceDataSource.Unknown);
    this.selectionPhase.set(SaleSelectionPhase.Idle);
  }

  close(): void { if (!this.busy()) { ++this.detailPublicationGeneration; this.openPublication = null; this.detail.set(null); this.detailSource.set(SurfaceDataSource.Unknown); this.notice.set(null); } }
  can(action: AllowedAction): boolean {
    const detail = this.detail();
    return !this.busy() && !this.commands.unresolved() && !this.commands.blocked().blocksWrites && !!detail && this.detailMatchesSelection(detail) && this.session.can(action.permission) && detail.status.permits(action, detail);
  }

  execute(action: AllowedAction, reason: string, amount?: unknown, method = PaymentMethod.Cash, paymentId?: number): void {
    const original = this.detail();
    if (!original || !this.can(action)) return;
    const reversalReason = action === AllowedAction.ReversePayment ? ReversalReason.fromInput(reason) : null;
    const requestedAmount = TicketMoney.fromDecimal(amount);
    if (action === AllowedAction.CapturePayment && (!requestedAmount || requestedAmount.cents <= 0n || method === PaymentMethod.Unknown)) return;
    if (action === AllowedAction.ReversePayment && (!reversalReason || !original.payments.some(payment => payment.paymentId === paymentId && payment.reversibility.permitsReverse))) return;
    const generation = this.generation;
    const selectedAtStart = this.selectedOperationId();
    const request = ++this.commandGeneration;
    const publication = ++this.detailPublicationGeneration;
    this.busy.set(true); this.detail.set(null); this.detailSource.set(SurfaceDataSource.Unknown); this.notice.set(null);
    if (selectedAtStart) this.selectionPhase.set(SaleSelectionPhase.Loading);
    const active = () => generation === this.generation && request === this.commandGeneration && this.session.can(action.permission);
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
          ...current.identity, originalPaymentId: paymentId, reason: reversalReason!.wire, evidenceRef: `rev-${paymentId}`,
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
      next: detail => { if (active()) {
        const publish = publication === this.detailPublicationGeneration && (selectedAtStart ? selectedAtStart.equals(this.selectedOperationId()) : this.selectedOperationId() === null);
        if (publish) { this.detail.set(detail); this.detailSource.set(SurfaceDataSource.Real); if (selectedAtStart) this.selectionPhase.set(SaleSelectionPhase.Ready); }
        this.busy.set(false);
      } },
      error: () => { if (generation === this.generation && request === this.commandGeneration) {
        this.busy.set(false);
        if (publication === this.detailPublicationGeneration) {
          this.detailSource.set(SurfaceDataSource.Unavailable); this.notice.set('No se pudo confirmar la operación. Consultá la venta antes de continuar.');
          if (selectedAtStart?.equals(this.selectedOperationId())) this.selectionPhase.set(SaleSelectionPhase.Unavailable);
        }
      } },
    });
  }

  consultReceipt(): void {
    if (this.busy()) return;
    const generation = this.generation;
    const publication = this.detailPublicationGeneration;
    const selectedAtStart = this.selectedOperationId();
    this.busy.set(true);
    if (!selectedAtStart) this.detailSource.set(SurfaceDataSource.Unknown);
    if (this.sales.unresolved()) {
      this.sales.consult().subscribe(result => {
        this.completeConsult(generation, publication, selectedAtStart, result.outcome.label);
      });
      return;
    }
    this.commands.consult().subscribe(receipt => {
      this.completeConsult(generation, publication, selectedAtStart, receipt.failure.label || receipt.outcome.label);
    });
  }

  private completeConsult(generation: number, publication: number, selectedAtStart: OperationId | null, notice: string): void {
    if (generation !== this.generation) return;
    this.busy.set(false);
    if (publication !== this.detailPublicationGeneration) return;
    if (selectedAtStart?.equals(this.selectedOperationId())) {
      this.selectOperation(selectedAtStart);
      this.notice.set(notice);
      return;
    }
    this.detail.set(null); this.notice.set(notice);
  }

  private read(operationId: string): Observable<DurableSaleDetail> {
    return this.http.get<unknown>(`${API_BASE}/sales/operations/${encodeURIComponent(operationId)}`).pipe(map(response => {
      const detail = PosWireMapper.durableDetail(response, operationId);
      if (!detail) throw new Error('Detalle inválido');
      return detail;
    }));
  }

  private detailMatchesSelection(detail: DurableSaleDetail): boolean {
    const selection = this.selectedOperationId();
    return selection === null || selection.equals(OperationId.fromWire(detail.identity.operationId));
  }
}
