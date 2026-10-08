import { StaffPermission } from './session-types';
import { PaymentMethod, PaymentStatus } from './pos-types';
import { PaymentCoverage, TicketIdentity, TicketMoney } from './ticket-transition';

export class AllowedAction {
  static readonly CapturePayment = new AllowedAction('CAPTURE_PAYMENT', 'Completar pago', StaffPermission.PaymentCapture);
  static readonly Commit = new AllowedAction('COMMIT', 'Confirmar venta', StaffPermission.SaleCommit);
  static readonly Release = new AllowedAction('RELEASE', 'Liberar reserva', StaffPermission.SaleRelease);
  static readonly ReversePayment = new AllowedAction('REVERSE_PAYMENT', 'Reversar pago', StaffPermission.PaymentReverse);
  static readonly Unknown = new AllowedAction('UNKNOWN', 'Acción no disponible', StaffPermission.Unknown);
  static readonly values = [AllowedAction.CapturePayment, AllowedAction.Commit, AllowedAction.Release, AllowedAction.ReversePayment] as const;
  private constructor(readonly wire: string, readonly label: string, readonly permission: StaffPermission) {}
  static fromWire(raw: unknown): AllowedAction { return AllowedAction.values.find(value => value.wire === raw) ?? AllowedAction.Unknown; }
}

export class DurableSaleState {
  static readonly PendingReservation = new DurableSaleState('PENDING_RESERVATION', 'Reserva pendiente');
  static readonly Reserved = new DurableSaleState('RESERVED', 'Reservada', true);
  static readonly PaymentCaptured = new DurableSaleState('PAYMENT_CAPTURED', 'Pago registrado', true);
  static readonly CommitPending = new DurableSaleState('COMMIT_PENDING', 'Confirmación pendiente');
  static readonly Committed = new DurableSaleState('COMMITTED', 'Venta confirmada');
  static readonly ReleasePending = new DurableSaleState('RELEASE_PENDING', 'Liberación pendiente');
  static readonly Released = new DurableSaleState('RELEASED', 'Reserva liberada');
  static readonly ReconciliationRequired = new DurableSaleState('RECONCILIATION_REQUIRED', 'Requiere reconciliación');
  static readonly LegacyIncomplete = new DurableSaleState('LEGACY_INCOMPLETE', 'Histórico incompleto · sólo consulta');
  static readonly Unknown = new DurableSaleState('UNKNOWN', 'Estado desconocido · sólo consulta');
  static readonly values = [DurableSaleState.PendingReservation, DurableSaleState.Reserved, DurableSaleState.PaymentCaptured,
    DurableSaleState.CommitPending, DurableSaleState.Committed, DurableSaleState.ReleasePending, DurableSaleState.Released,
    DurableSaleState.ReconciliationRequired, DurableSaleState.LegacyIncomplete] as const;
  private constructor(readonly wire: string, readonly label: string, readonly acceptsCommands = false) {}
  static fromWire(raw: unknown): DurableSaleState { return DurableSaleState.values.find(value => value.wire === raw) ?? DurableSaleState.Unknown; }
  permits(action: AllowedAction, sale: DurableSaleDetail): boolean {
    if (!this.acceptsCommands || !sale.valid || !sale.allowedActions.includes(action)) return false;
    if (action === AllowedAction.CapturePayment) return (sale.coverage === PaymentCoverage.Partial || sale.coverage === PaymentCoverage.Unpaid) && !!sale.pending && sale.pending.cents > 0n;
    if (action === AllowedAction.Commit) return sale.coverage === PaymentCoverage.Paid && sale.pending?.cents === 0n;
    if (action === AllowedAction.Release) return sale.coverage === PaymentCoverage.Unpaid && !!sale.total && sale.pending?.cents === sale.total.cents;
    if (action === AllowedAction.ReversePayment) return sale.hasPaymentHistory === true && sale.payments.some(payment => payment.status === PaymentStatus.Captured);
    return false;
  }
}

export interface DurableSaleSummary {
  readonly identity: TicketIdentity;
  readonly cashSessionId: number;
  readonly cashierId: number;
  readonly createdBy: number | null;
  readonly status: DurableSaleState;
  readonly total: TicketMoney | null;
}
export interface DurableSaleLine {
  readonly sku: string;
  readonly productName: string;
  readonly quantity: number;
  readonly total: TicketMoney | null;
}
export interface DurableSalePayment {
  readonly paymentId: number;
  readonly status: PaymentStatus;
  readonly method: PaymentMethod;
  readonly amount: TicketMoney | null;
  readonly fee: TicketMoney | null;
}
export interface DurableSaleDetail extends DurableSaleSummary {
  readonly pending: TicketMoney | null;
  readonly coverage: PaymentCoverage;
  readonly hasPaymentHistory: boolean | null;
  readonly lines: readonly DurableSaleLine[];
  readonly payments: readonly DurableSalePayment[];
  readonly allowedActions: readonly AllowedAction[];
  readonly valid: boolean;
  readonly receipt: string | null;
  readonly reservationRef: string | null;
  readonly pendingCommand: DurableCommandKind | null;
}
export interface DurableSalePage { readonly items: readonly DurableSaleSummary[]; readonly nextCursor: string | null; }

export class DurableCommandKind {
  static readonly Reserve = new DurableCommandKind('RESERVE', 'Reserva pendiente');
  static readonly Commit = new DurableCommandKind('COMMIT', 'Confirmación pendiente');
  static readonly Release = new DurableCommandKind('RELEASE', 'Liberación pendiente');
  static readonly Unknown = new DurableCommandKind('UNKNOWN', 'Comando desconocido');
  private constructor(readonly wire: string, readonly label: string) {}
  static fromWire(raw: unknown): DurableCommandKind {
    return [DurableCommandKind.Reserve, DurableCommandKind.Commit, DurableCommandKind.Release].find(value => value.wire === raw) ?? DurableCommandKind.Unknown;
  }
}
