import { Observable, concatMap, map, of, throwError } from 'rxjs';
import { SaleAction, SaleStatus } from '../../core/domain/pos-types';
import { CapturedPayment, PaymentAttempt, TicketIdentity, TicketSnapshot, TicketTransitionPolicy } from '../../core/domain/ticket-transition';
import { PosWireMapper } from '../../core/infrastructure/pos-wire-mapper';

export interface TicketFlowPort {
  reserve(body: Readonly<Record<string, unknown>>): Observable<unknown>;
  capture(attempt: PaymentAttempt): Observable<unknown>;
  refresh(identity: TicketIdentity): Observable<unknown>;
}

export interface TicketAttemptContext {
  readonly identity: TicketIdentity;
  readonly reservation: Readonly<Record<string, unknown>>;
  readonly payments: readonly PaymentAttempt[];
}

export interface TicketFlowResult {
  readonly snapshot: TicketSnapshot;
  readonly payments: readonly CapturedPayment[];
}

export class ReserveTicketStep {
  constructor(private readonly port: TicketFlowPort) {}
  execute(context: TicketAttemptContext): Observable<TicketSnapshot> {
    return this.port.reserve(context.reservation).pipe(map((response) => {
      const snapshot = PosWireMapper.ticket(response, context.identity);
      if (snapshot.status !== SaleStatus.Reserved || !TicketTransitionPolicy.decide(snapshot, SaleAction.Capture, context.payments[0]).permitsWrite) {
        throw new Error('La reserva no habilita el cobro. Consultá el estado de la operación.');
      }
      return snapshot;
    }));
  }
}

export class CapturePaymentStep {
  constructor(private readonly port: TicketFlowPort) {}
  execute(snapshot: TicketSnapshot, attempt: PaymentAttempt): Observable<CapturedPayment> {
    const decision = TicketTransitionPolicy.decide(snapshot, SaleAction.Capture, attempt);
    if (!decision.permitsWrite) return throwError(() => new Error(decision.label));
    return this.port.capture(attempt).pipe(map((response) => {
      const payment = PosWireMapper.capturedPayment(response, attempt);
      if (!payment) throw new Error('No se pudo comprobar el pago. Consultá el estado antes de continuar.');
      return payment;
    }));
  }
}

export class RefreshTicketStep {
  constructor(private readonly port: TicketFlowPort) {}
  execute(identity: TicketIdentity): Observable<TicketSnapshot> {
    return this.port.refresh(identity).pipe(map((response) => {
      const snapshot = PosWireMapper.ticket(response, identity);
      if (!snapshot.evidenceValid) throw new Error('No se pudo comprobar la identidad o evidencia de la venta.');
      return snapshot;
    }));
  }
}

/** Orders independently responsible steps; every capture is followed by a correlated refresh. */
export class TicketPaymentJourney {
  constructor(
    private readonly reserve: ReserveTicketStep,
    private readonly capture: CapturePaymentStep,
    private readonly refresh: RefreshTicketStep,
    private readonly onProgress: (result: TicketFlowResult) => void = () => undefined,
    private readonly onCaptured: (payment: CapturedPayment) => void = () => undefined,
  ) {}

  execute(context: TicketAttemptContext): Observable<TicketFlowResult> {
    return this.reserve.execute(context).pipe(concatMap((snapshot) => {
      let flow: Observable<TicketFlowResult> = of({ snapshot, payments: [] });
      for (const attempt of context.payments) {
        flow = flow.pipe(concatMap((previous) => this.capture.execute(previous.snapshot, attempt).pipe(
          concatMap((payment) => {
            if (previous.payments.some((existing) => existing.paymentId === payment.paymentId)) {
              return throwError(() => new Error('El pago recibido ya pertenece a otro paso del intento.'));
            }
            // Retain correlated capture evidence even if the subsequent read fails.
            // Only the validated refresh below may advance the journey.
            this.onCaptured(payment);
            return this.refresh.execute(context.identity).pipe(map((next) => {
              // A stale GET cannot authorize a second capture of money already collected.
              if (!previous.snapshot.pending || !next.pending ||
                  next.pending.cents !== previous.snapshot.pending.cents - payment.amount.cents) {
                throw new Error('El saldo consultado no confirma el pago.');
              }
              const result = { snapshot: next, payments: [...previous.payments, payment] };
              this.onProgress(result);
              return result;
            }));
          }),
        )));
      }
      return flow;
    }));
  }
}
