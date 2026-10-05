import { PaymentMethod, PaymentStatus, SaleAction, SaleStatus } from './pos-types';

/** Exact NUMERIC(14,2) amount. No rounding or implicit coercion is permitted. */
export class TicketMoney {
  private constructor(readonly cents: bigint) {}

  static fromDecimal(raw: unknown): TicketMoney | null {
    if (typeof raw !== 'number' && typeof raw !== 'string') return null;
    if (typeof raw === 'number' && !Number.isFinite(raw)) return null;
    const match = /^(-?)(\d+)(?:\.(\d+))?$/.exec(String(raw));
    if (!match) return null;
    const fraction = (match[3] ?? '').replace(/0+$/, '');
    if (fraction.length > 2) return null;
    const cents = (BigInt(match[2]) * 100n + BigInt(fraction.padEnd(2, '0'))) * (match[1] ? -1n : 1n);
    if (cents > 99999999999999n || cents < -99999999999999n) return null;
    return new TicketMoney(cents);
  }

  get decimal(): string {
    const absolute = this.cents < 0n ? -this.cents : this.cents;
    return `${this.cents < 0n ? '-' : ''}${absolute / 100n}.${String(absolute % 100n).padStart(2, '0')}`;
  }
}

export class PaymentCoverage {
  static readonly Unpaid = new PaymentCoverage('UNPAID', 'sin pagos');
  static readonly Partial = new PaymentCoverage('PARTIAL', 'pago parcial');
  static readonly Paid = new PaymentCoverage('PAID', 'pagado');
  static readonly InvalidUnknown = new PaymentCoverage('INVALID_UNKNOWN', 'cobertura desconocida');
  private constructor(readonly wire: string, readonly label: string) {}
  static fromWire(raw: unknown): PaymentCoverage {
    switch (raw) {
      case 'UNPAID': return PaymentCoverage.Unpaid;
      case 'PARTIAL': return PaymentCoverage.Partial;
      case 'PAID': return PaymentCoverage.Paid;
      default: return PaymentCoverage.InvalidUnknown;
    }
  }
}

export class TransitionKind {
  static readonly NewCommand = new TransitionKind('acción disponible', true);
  static readonly RecoverExistingCommand = new TransitionKind('consultar operación pendiente', false);
  static readonly TerminalReplay = new TransitionKind('operación ya completada', false);
  static readonly Denied = new TransitionKind('acción denegada', false);
  private constructor(readonly label: string, readonly permitsWrite: boolean) {}
}

export class TransitionDenial {
  static readonly None = new TransitionDenial('');
  static readonly InvalidEvidence = new TransitionDenial('La identidad o evidencia de la venta no es válida.');
  static readonly IneligibleState = new TransitionDenial('El estado de la venta no permite esta acción.');
  static readonly InvalidMoney = new TransitionDenial('El importe o saldo no es válido.');
  static readonly IncompleteCoverage = new TransitionDenial('La venta requiere cobertura exacta del total.');
  static readonly PaymentHistory = new TransitionDenial('La reserva tiene historial de pagos.');
  static readonly ActiveAttempt = new TransitionDenial('Hay una acción en curso.');
  private constructor(readonly label: string) {}
}

export class TransitionDecision {
  static readonly NewCommand = new TransitionDecision(TransitionKind.NewCommand, TransitionDenial.None);
  static readonly RecoverExistingCommand = new TransitionDecision(TransitionKind.RecoverExistingCommand, TransitionDenial.None);
  static readonly TerminalReplay = new TransitionDecision(TransitionKind.TerminalReplay, TransitionDenial.None);
  private constructor(readonly kind: TransitionKind, readonly reason: TransitionDenial) {}
  static deny(reason: TransitionDenial): TransitionDecision { return new TransitionDecision(TransitionKind.Denied, reason); }
  get permitsWrite(): boolean { return this.kind.permitsWrite; }
  get label(): string { return this.reason.label || this.kind.label; }
}

export interface TicketIdentity {
  readonly clientInstanceId: string;
  readonly deviceId: string;
  readonly saleId: string;
  readonly operationId: string;
}

export interface TicketSnapshot {
  readonly identity: TicketIdentity;
  readonly status: SaleStatus;
  readonly evidenceValid: boolean;
  readonly receipt: string | null;
  readonly reservationRef: string | null;
  readonly total: TicketMoney | null;
  readonly pending: TicketMoney | null;
  readonly coverage: PaymentCoverage;
  readonly hasPaymentHistory: boolean | null;
  readonly blocked: boolean;
  readonly retired: boolean;
}

export interface PaymentAttempt {
  readonly identity: TicketIdentity;
  readonly method: PaymentMethod;
  readonly amount: TicketMoney;
  readonly fee: TicketMoney;
}

export interface CapturedPayment {
  readonly paymentId: number;
  readonly status: PaymentStatus;
  readonly amount: TicketMoney;
  readonly fee: TicketMoney;
}

export interface ReservationAmounts {
  readonly price: TicketMoney | null;
  readonly discount: TicketMoney | null;
  readonly amount: TicketMoney | null;
  readonly fee: TicketMoney | null;
  readonly extra: TicketMoney | null;
  readonly secondMethod: PaymentMethod;
}

export class TicketTransitionPolicy {
  private constructor() {}
  static beginReserve(active: boolean, counterReady: boolean): TransitionDecision {
    if (active) return TransitionDecision.deny(TransitionDenial.ActiveAttempt);
    return counterReady ? TransitionDecision.NewCommand : TransitionDecision.deny(TransitionDenial.IneligibleState);
  }

  static reservationAmounts(input: ReservationAmounts): TransitionDecision {
    const { price, discount, amount, fee, extra, secondMethod } = input;
    if (!price || !discount || !amount || !fee || !extra || price.cents <= 0n || discount.cents < 0n ||
        discount.cents >= price.cents || amount.cents <= 0n || fee.cents < 0n || extra.cents < 0n ||
        (extra.cents > 0n && secondMethod === PaymentMethod.Unknown) || amount.cents + extra.cents > price.cents - discount.cents) {
      return TransitionDecision.deny(TransitionDenial.InvalidMoney);
    }
    return TransitionDecision.NewCommand;
  }
  static decide(snapshot: TicketSnapshot | null, action: SaleAction, attempt?: PaymentAttempt): TransitionDecision {
    if (!snapshot?.evidenceValid || snapshot.blocked || snapshot.retired) return TransitionDecision.deny(TransitionDenial.InvalidEvidence);
    if (snapshot.status === SaleStatus.Unknown || snapshot.status === SaleStatus.ReconciliationRequired) return TransitionDecision.deny(TransitionDenial.IneligibleState);
    if ((action === SaleAction.Commit && snapshot.status === SaleStatus.Committed) ||
        (action === SaleAction.Release && snapshot.status === SaleStatus.Released)) return TransitionDecision.TerminalReplay;
    // Pending snapshots retain read-only recovery; the UI never constructs a replacement command.
    if ((action === SaleAction.Commit && snapshot.status === SaleStatus.CommitPending) ||
        (action === SaleAction.Release && snapshot.status === SaleStatus.ReleasePending)) return TransitionDecision.RecoverExistingCommand;
    if (snapshot.status !== SaleStatus.Reserved && snapshot.status !== SaleStatus.PaymentCaptured) return TransitionDecision.deny(TransitionDenial.IneligibleState);
    if (!snapshot.total || snapshot.total.cents <= 0n || !snapshot.pending || snapshot.pending.cents < 0n ||
        snapshot.pending.cents > snapshot.total.cents || snapshot.coverage === PaymentCoverage.InvalidUnknown) return TransitionDecision.deny(TransitionDenial.InvalidMoney);
    if (action === SaleAction.Capture) {
      if (!attempt || attempt.method === PaymentMethod.Unknown || attempt.amount.cents <= 0n || attempt.fee.cents < 0n ||
          attempt.amount.cents > snapshot.pending.cents) return TransitionDecision.deny(TransitionDenial.InvalidMoney);
      if (!sameTicketIdentity(snapshot.identity, attempt.identity)) return TransitionDecision.deny(TransitionDenial.InvalidEvidence);
      return TransitionDecision.NewCommand;
    }
    if (action === SaleAction.Commit) return snapshot.coverage === PaymentCoverage.Paid && snapshot.pending.cents === 0n
      ? TransitionDecision.NewCommand : TransitionDecision.deny(TransitionDenial.IncompleteCoverage);
    if (action === SaleAction.Release) return snapshot.status === SaleStatus.Reserved && snapshot.hasPaymentHistory === false && snapshot.coverage === PaymentCoverage.Unpaid
      ? TransitionDecision.NewCommand : TransitionDecision.deny(TransitionDenial.PaymentHistory);
    if (action === SaleAction.Reverse) return snapshot.hasPaymentHistory === true &&
      (snapshot.coverage === PaymentCoverage.Partial || snapshot.coverage === PaymentCoverage.Paid)
      ? TransitionDecision.NewCommand : TransitionDecision.deny(TransitionDenial.PaymentHistory);
    return TransitionDecision.deny(TransitionDenial.IneligibleState);
  }
}

export function sameTicketIdentity(left: TicketIdentity, right: TicketIdentity): boolean {
  return left.clientInstanceId === right.clientInstanceId && left.deviceId === right.deviceId &&
    left.saleId === right.saleId && left.operationId === right.operationId;
}
