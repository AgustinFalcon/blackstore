import { Injectable, inject } from '@angular/core';
import { concatMap, from, toArray } from 'rxjs';
import { ApiFailure } from '../../../core/http/api-error';
import { PaymentStatus, PosPaymentMethod, SaleStatus } from '../domain/closed-status';
import { LOCAL_CLIENT_INSTANCE_ID, RecordedPayment, SaleSnapshot, TicketLineDraft } from '../domain/pos.models';
import { BlackstoreApiService, ReserveSaleBody } from '../infrastructure/blackstore-api.service';
import { screenCopy } from './load-counter.usecase';
import { PosStore } from './pos.store';

export interface ReserveInput {
  readonly line: TicketLineDraft;
  readonly cashAmount: number;
  readonly feeAmount: number;
  readonly secondMethod: PosPaymentMethod;
  readonly secondAmount: number;
}

@Injectable({ providedIn: 'root' })
export class SaleTicketUseCase {
  private readonly api = inject(BlackstoreApiService);
  private readonly store = inject(PosStore);

  reserve(input: ReserveInput): void {
    if (this.store.reserveDisabled()) return;
    const session = this.store.openSession();
    if (!session) return;
    if (input.line.discountAmount > input.line.originalUnitPrice || input.cashAmount <= 0 || input.line.quantity < 1) {
      this.store.ticketError.set('Revisá los datos del formulario.');
      return;
    }
    const existing = this.store.activeSale();
    const saleId = this.store.stockBlocked() && existing ? existing.saleId : crypto.randomUUID();
    const operationId = crypto.randomUUID();
    const body: ReserveSaleBody = {
      clientInstanceId: LOCAL_CLIENT_INSTANCE_ID,
      deviceId: `terminal-${this.store.workspace()?.terminalId ?? session.terminalId}`,
      saleId,
      operationId,
      cashSessionId: session.id,
      variantId: input.line.variantId,
      quantity: input.line.quantity,
      expectedPriceVersion: input.line.priceVersion,
      sku: input.line.sku,
      productName: input.line.productName,
      originalUnitPrice: input.line.originalUnitPrice,
      discountAmount: input.line.discountAmount,
    };
    this.store.ticketBusy.set(true);
    this.store.ticketError.set(null);
    this.store.ticketMessage.set(null);
    this.api.reserve(body).subscribe((reserved) => {
      if (!reserved.ok) {
        this.store.ticketBusy.set(false);
        this.fail(reserved.failure, saleId, operationId, input.line, session.id);
        return;
      }
      const draft = snapshotFrom(body, reserved.data.status, reserved.data.receipt, reserved.data.reservationRef, input.line, []);
      this.capturePayments(draft, input);
    });
  }

  retryPayments(input: ReserveInput): void {
    const current = this.store.activeSale();
    if (!current || this.store.writesDisabled()) return;
    this.store.ticketBusy.set(true);
    this.store.ticketError.set(null);
    this.capturePayments(current, input);
  }

  refresh(): void {
    const current = this.store.activeSale();
    if (!current) return;
    this.api.sale(current.operationId).subscribe((result) => {
      if (!result.ok) {
        this.fail(result.failure, current.saleId, current.operationId, current.lines[0], current.cashSessionId);
        return;
      }
      const next = { ...current, status: result.data.status, receipt: result.data.receipt, reservationRef: result.data.reservationRef };
      this.store.rememberSale(next);
      this.store.ticketMessage.set(`Estado ${next.status.label}`);
    });
  }

  commit(): void {
    const current = this.store.activeSale();
    if (!current || !current.status.canFinish || this.store.writesDisabled()) return;
    this.store.ticketBusy.set(true);
    this.store.ticketError.set(null);
    this.api.commit(current.operationId).subscribe((result) => {
      this.store.ticketBusy.set(false);
      if (!result.ok) {
        this.fail(result.failure, current.saleId, current.operationId, current.lines[0], current.cashSessionId);
        return;
      }
      const next = { ...current, status: result.data.status, receipt: result.data.receipt, reservationRef: result.data.reservationRef };
      this.store.rememberSale(next);
      this.store.ticketMessage.set('Venta registrada en el simulador local. Esta pantalla no emite.');
    });
  }

  release(): void {
    const current = this.store.activeSale();
    if (!current || !current.status.canFinish || this.store.writesDisabled()) return;
    this.store.ticketBusy.set(true);
    this.store.ticketError.set(null);
    this.api.release(current.operationId).subscribe((result) => {
      this.store.ticketBusy.set(false);
      if (!result.ok) {
        this.fail(result.failure, current.saleId, current.operationId, current.lines[0], current.cashSessionId);
        return;
      }
      const next = { ...current, status: result.data.status, receipt: result.data.receipt, reservationRef: result.data.reservationRef };
      this.store.rememberSale(next);
      this.store.ticketMessage.set('Reserva liberada en el simulador local.');
    });
  }

  reverse(paymentId: number, reason: string): void {
    const current = this.store.activeSale();
    if (!current || this.store.writesDisabled() || reason.trim().length === 0) return;
    this.store.ticketBusy.set(true);
    this.store.ticketError.set(null);
    this.api
      .reverse(paymentId, { operationId: current.operationId, reason: reason.trim(), evidenceRef: `rev-${paymentId}` })
      .subscribe((result) => {
        this.store.ticketBusy.set(false);
        if (!result.ok) {
          this.fail(result.failure, current.saleId, current.operationId, current.lines[0], current.cashSessionId);
          return;
        }
        const payments = current.payments.map((payment) => (payment.id === paymentId ? { ...payment, status: PaymentStatus.Refunded } : payment));
        this.store.rememberSale({ ...current, payments });
        this.store.ticketMessage.set('Reversa registrada. El ticket no se borra.');
      });
  }

  loadSnapshot(saleId: string): void {
    const local = this.store.salesById()[saleId] ?? null;
    this.store.readSale.set(local);
    this.store.readMissing.set(!local);
    this.store.readError.set(null);
    if (!local) {
      this.store.readLoading.set(false);
      return;
    }
    this.store.readLoading.set(true);
    this.api.sale(local.operationId).subscribe((result) => {
      this.store.readLoading.set(false);
      if (!result.ok) {
        if (result.failure.errorCode === 'NOT_FOUND') {
          this.store.readError.set('El proceso ya no tiene esta operación. Se muestra el snapshot de esta consola.');
          return;
        }
        this.store.readError.set(screenCopy(result.failure, 'No se pudo consultar la venta'));
        return;
      }
      const next = { ...local, status: result.data.status, receipt: result.data.receipt, reservationRef: result.data.reservationRef };
      this.store.rememberSale(next);
      this.store.readSale.set(next);
      this.store.readMissing.set(false);
    });
  }

  private capturePayments(draft: SaleSnapshot, input: ReserveInput): void {
    const payments: { method: PosPaymentMethod; amount: number; feeAmount: number }[] = [
      { method: PosPaymentMethod.Cash, amount: input.cashAmount, feeAmount: input.feeAmount },
    ];
    if (input.secondAmount > 0) payments.push({ method: input.secondMethod, amount: input.secondAmount, feeAmount: 0 });
    from(payments)
      .pipe(concatMap((payment) => this.api.pay({ operationId: draft.operationId, ...payment })))
      .pipe(toArray())
      .subscribe((results) => {
        this.store.ticketBusy.set(false);
        const recorded: RecordedPayment[] = [...draft.payments];
        for (let index = 0; index < results.length; index += 1) {
          const result = results[index];
          const requested = payments[index];
          if (!result.ok) {
            this.store.rememberSale({ ...draft, payments: recorded });
            this.fail(result.failure, draft.saleId, draft.operationId, input.line, draft.cashSessionId);
            return;
          }
          recorded.push({
            id: result.data.id,
            method: requested.method,
            amount: result.data.amount,
            feeAmount: result.data.feeAmount,
            status: result.data.status,
          });
        }
        const next = { ...draft, payments: recorded };
        this.store.rememberSale(next);
        this.store.ticketMessage.set(`Reserva ${next.status.label}. El cobro quedó en el simulador local.`);
      });
  }

  private fail(failure: ApiFailure, saleId: string, operationId: string, line: TicketLineDraft | undefined, cashSessionId: number): void {
    if (failure.errorCode === 'CAPABILITY_DISABLED') this.store.blockWrites();
    if (failure.errorCode === 'CATALOG_VERSION_STALE') this.store.markCatalogStale();
    if (failure.errorCode === 'INSUFFICIENT_STOCK') {
      const session = this.store.openSession();
      if (line && session) {
        const current = this.store.activeSale();
        this.store.rememberSale({
          saleId,
          operationId: current?.saleId === saleId ? current.operationId : operationId,
          clientInstanceId: LOCAL_CLIENT_INSTANCE_ID,
          deviceId: `terminal-${session.terminalId}`,
          cashSessionId,
          status: current?.status ?? SaleStatus.PendingReservation,
          receipt: current?.receipt ?? null,
          reservationRef: current?.reservationRef ?? null,
          lines: [line],
          payments: current?.saleId === saleId ? current.payments : [],
        });
      }
      this.store.blockStock(failure.availableQuantity);
    }
    this.store.ticketError.set(screenCopy(failure, 'No se pudo completar la venta'));
  }
}

function snapshotFrom(
  body: ReserveSaleBody,
  status: SaleStatus,
  receipt: string | null,
  reservationRef: string | null,
  line: TicketLineDraft,
  payments: readonly RecordedPayment[],
): SaleSnapshot {
  return {
    saleId: body.saleId,
    operationId: body.operationId,
    clientInstanceId: body.clientInstanceId,
    deviceId: body.deviceId,
    cashSessionId: body.cashSessionId,
    status,
    receipt,
    reservationRef,
    lines: [line],
    payments,
  };
}
