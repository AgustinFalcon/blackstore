import { Observable, concatMap, map, of, throwError, timer } from 'rxjs';
import {AwaitReservationReadStep,ReservationReadState} from '../../core/infrastructure/await-reservation-read-step';
import { SaleAction, SaleStatus } from '../../core/domain/pos-types';
import { CapturedPayment, PaymentAttempt, PaymentLedgerSemantics, TicketIdentity, TicketSnapshot, TicketTransitionPolicy } from '../../core/domain/ticket-transition';
import { PosWireMapper } from '../../core/infrastructure/pos-wire-mapper';
import { CartDraft } from '../../core/domain/cart';
import { SaleAdmission, SaleAdmissionOutcome, SaleCommand, SaleCommandKind, SaleReservationLineV2 } from '../../core/domain/sale-command';

export interface TicketFlowPort {
  reserve(body: Readonly<Record<string, unknown>>): Observable<unknown>;
  capture(attempt: PaymentAttempt): Observable<unknown>;
  capturePayment?(attempt: PaymentAttempt): Observable<CapturedPayment>;
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

export interface ReservationContext { readonly clientInstanceId:string; readonly deviceId:string; readonly terminalId:number;
  readonly cashSessionId:number; readonly cashTerminalId:number; }

/** Validates and freezes the complete draft before any identity or command is created. */
export class ValidateCartStep {
  execute(draft:CartDraft):readonly SaleReservationLineV2[]{return draft.freeze();}
}

/** Creates one immutable V3 reservation command after checking the POS/cash context boundary. */
export class PrepareReservationStep {
  execute(lines:readonly SaleReservationLineV2[],context:ReservationContext,reason:string):SaleCommand {
    if(context.terminalId!==context.cashTerminalId)throw new Error('Caja y contexto POS no coinciden.');
    const identity:TicketIdentity=Object.freeze({clientInstanceId:context.clientInstanceId,deviceId:context.deviceId,
      saleId:crypto.randomUUID(),operationId:crypto.randomUUID()});
    return SaleCommand.createMultilineReserve(identity,context.cashSessionId,lines,reason||null);
  }
}

/** Resolves the durable asynchronous RESERVE exclusively through correlated GETs. */
export class AwaitReservationStep {
  constructor(
    private readonly port: TicketFlowPort,
    private readonly maxReads = 20,
    private readonly delayMs = 100,
    private readonly semantics = PaymentLedgerSemantics.Legacy,
  ) {}

  execute(identity: TicketIdentity, snapshot: TicketSnapshot): Observable<TicketSnapshot> {
    return new AwaitReservationReadStep(()=>this.port.refresh(identity).pipe(map(response=>PosWireMapper.ticket(response,identity,this.semantics))),
      next=>next.status===SaleStatus.Reserved && next.evidenceValid ? ReservationReadState.Reserved :
        next.status===SaleStatus.PendingReservation ? ReservationReadState.Pending : ReservationReadState.Unknown,
      ()=>true,this.maxReads,this.delayMs).execute(snapshot);
  }
}

export class ReserveTicketStep {
  constructor(
    private readonly port: TicketFlowPort,
    private readonly awaitReservation = new AwaitReservationStep(port),
  ) {}
  execute(context: TicketAttemptContext): Observable<TicketSnapshot> {
    return this.port.reserve(context.reservation).pipe(map((response) => {
      const snapshot = PosWireMapper.ticket(response, context.identity);
      return snapshot;
    }), concatMap((snapshot) => this.awaitReservation.execute(context.identity, snapshot)), map((snapshot) => {
      if (!TicketTransitionPolicy.decide(snapshot, SaleAction.Capture, context.payments[0]).permitsWrite) {
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
    if (this.port.capturePayment) return this.port.capturePayment(attempt);
    return this.port.capture(attempt).pipe(map((response) => {
      const payment = PosWireMapper.capturedPayment(response, attempt);
      if (!payment) throw new Error('No se pudo comprobar el pago. Consultá el estado antes de continuar.');
      return payment;
    }));
  }
}

export class RefreshTicketStep {
  constructor(private readonly port: TicketFlowPort, private readonly semantics = PaymentLedgerSemantics.Legacy) {}
  execute(identity: TicketIdentity): Observable<TicketSnapshot> {
    return this.port.refresh(identity).pipe(map((response) => {
      const snapshot = PosWireMapper.ticket(response, identity, this.semantics);
      if (!snapshot.evidenceValid) throw new Error('No se pudo comprobar la identidad o evidencia de la venta.');
      return snapshot;
    }));
  }
}

/** Captures one explicit payment and advances only after a correlated balance read confirms its delta. */
export class RefreshCoverageStep {
  constructor(private readonly capture:CapturePaymentStep,private readonly refresh:RefreshTicketStep,
    private readonly onCaptured:(payment:CapturedPayment,attempt:PaymentAttempt)=>void=()=>undefined){}
  execute(snapshot:TicketSnapshot,attempt:PaymentAttempt):Observable<{readonly payment:CapturedPayment;readonly next:TicketSnapshot}>{
    return this.capture.execute(snapshot,attempt).pipe(concatMap(payment=>{
      this.onCaptured(payment,attempt);
      return this.refresh.execute(attempt.identity).pipe(map(next=>{
        if(!snapshot.pending||!next.pending||next.pending.cents!==snapshot.pending.cents-payment.amount.cents)
          throw new Error('El saldo consultado no confirma el pago.');
        return Object.freeze({payment,next});
      }));
    }));
  }
}

/** Resolves an admitted terminal sale command only through correlated reads. */
export class AwaitTerminalSaleStep {
  constructor(private readonly port: TicketFlowPort, private readonly maxReads = 20, private readonly delayMs = 100,
    private readonly semantics = PaymentLedgerSemantics.Legacy) {}

  execute(identity: TicketIdentity, action: SaleAction): Observable<TicketSnapshot> {
    return this.read(identity, action, this.maxReads);
  }

  private read(identity: TicketIdentity, action: SaleAction, remaining: number): Observable<TicketSnapshot> {
    return this.port.refresh(identity).pipe(map(response => PosWireMapper.ticket(response, identity, this.semantics)), concatMap(snapshot => {
      const completed = action === SaleAction.Commit ? snapshot.status === SaleStatus.Committed : snapshot.status === SaleStatus.Released;
      if (completed && snapshot.evidenceValid) return of(snapshot);
      const waiting = action === SaleAction.Commit
        ? snapshot.status === SaleStatus.Reserved || snapshot.status === SaleStatus.PaymentCaptured || snapshot.status === SaleStatus.CommitPending
        : snapshot.status === SaleStatus.Reserved || snapshot.status === SaleStatus.PaymentCaptured || snapshot.status === SaleStatus.ReleasePending;
      if (!snapshot.evidenceValid || !waiting) return throwError(() => new Error('La lectura no confirma la finalización de la venta.'));
      if (remaining <= 1) return throwError(() => new Error('La finalización continúa pendiente. Consultá la operación.'));
      return timer(this.delayMs).pipe(concatMap(() => this.read(identity, action, remaining - 1)));
    }));
  }
}

/** Revalidates policy, admits the terminal command and waits for its correlated terminal read. */
export class CommitSaleStep {
  constructor(private readonly refresh:RefreshTicketStep,private readonly submit:(command:SaleCommand)=>Observable<SaleAdmission>,
    private readonly awaitTerminal:AwaitTerminalSaleStep){}
  execute(identity:TicketIdentity,cashSessionId:number,reason:string,action:SaleAction):Observable<TicketSnapshot>{
    return this.refresh.execute(identity).pipe(concatMap(snapshot=>{
      const decision=TicketTransitionPolicy.decide(snapshot,action);
      if(!decision.permitsWrite)return throwError(()=>new Error(decision.label));
      const kind=action===SaleAction.Commit?SaleCommandKind.Commit:SaleCommandKind.Release;
      return this.submit(SaleCommand.create(kind,identity,cashSessionId,{reason}));
    }),concatMap(result=>result.outcome===SaleAdmissionOutcome.Accepted?this.awaitTerminal.execute(identity,action):
      throwError(()=>new Error(result.outcome.label))));
  }
}

/** Orders independently responsible steps; every capture is followed by a correlated refresh. */
export class StartNewSale {
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

/** Compatibility name for the existing ordered reserve/capture journey. */
export class TicketPaymentJourney extends StartNewSale {}
