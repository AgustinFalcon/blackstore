import { StaffPermission } from './session-types';
import { ReconciliationOutcome } from './accounting-report';
import { TicketMoney } from './ticket-transition';

export class AccountingCommandKind {
  static readonly Open = new AccountingCommandKind('Open', StaffPermission.CashSessionOpen);
  static readonly Close = new AccountingCommandKind('Close', StaffPermission.CashSessionClose);
  static readonly Expense = new AccountingCommandKind('Expense', StaffPermission.ExpenseRecord);
  static readonly Capture = new AccountingCommandKind('Capture', StaffPermission.PaymentCapture);
  static readonly Reverse = new AccountingCommandKind('Reverse', StaffPermission.PaymentReverse);
  static readonly Unknown = new AccountingCommandKind('Unknown', StaffPermission.Unknown);
  private constructor(readonly wire: string, readonly permission: StaffPermission) {}
  static fromWire(raw: unknown): AccountingCommandKind { return [this.Open, this.Close, this.Expense, this.Capture, this.Reverse].find(value => value.wire === raw) ?? this.Unknown; }
}

export class ExpenseOperation {
  static readonly Accrue = new ExpenseOperation('ACCRUE');
  static readonly AccrueAndSettle = new ExpenseOperation('ACCRUE_AND_SETTLE');
  static readonly SettleExisting = new ExpenseOperation('SETTLE_EXISTING');
  static readonly Unknown = new ExpenseOperation('UNKNOWN');
  private constructor(readonly wire: string) {}
  static fromWire(raw: unknown): ExpenseOperation {
    return [this.Accrue, this.AccrueAndSettle, this.SettleExisting].find(item => item.wire === raw) ?? this.Unknown;
  }
}

export class CommandOutcome {
  static readonly Committed = new CommandOutcome('COMMITTED', 'Operación registrada');
  static readonly NotFound = new CommandOutcome('NOT_FOUND', 'Sin recibo visible: resultado pendiente de comprobación');
  static readonly Unavailable = new CommandOutcome('UNAVAILABLE', 'Recibo temporalmente no disponible');
  static readonly Unknown = new CommandOutcome('UNKNOWN', 'Resultado desconocido: consultá el recibo');
  private constructor(readonly wire: string, readonly label: string) {}
  static fromWire(raw: unknown): CommandOutcome {
    return [this.Committed, this.NotFound, this.Unavailable].find(item => item.wire === raw) ?? this.Unknown;
  }
}

export class CommandFailure {
  static readonly None = new CommandFailure(null, '');
  static readonly NotVisible = new CommandFailure('NOT_VISIBLE', 'Operación no disponible');
  static readonly Forbidden = new CommandFailure('FORBIDDEN', 'Permiso insuficiente');
  static readonly Validation = new CommandFailure('VALIDATION', 'Revisá los datos');
  static readonly Closed = new CommandFailure('CLOSED', 'Caja cerrada');
  static readonly TransitionConflict = new CommandFailure('PAYMENT_TRANSITION_CONFLICT', 'La venta cambió');
  static readonly CashConflict = new CommandFailure('CASH_SESSION_CONFLICT', 'La caja cambió');
  static readonly NonTerminalSale = new CommandFailure('NON_TERMINAL_SALE', 'Hay ventas pendientes de resolver');
  static readonly PayloadMismatch = new CommandFailure('PAYLOAD_MISMATCH', 'El comando tiene otro contenido');
  static readonly LegacyDisabled = new CommandFailure('LEGACY_CONTRACT_DISABLED', 'Contrato anterior deshabilitado', true);
  static readonly NotActivated = new CommandFailure('NOT_ACTIVATED', 'Contabilidad aún no activada', true);
  static readonly Paused = new CommandFailure('PAUSED', 'Operaciones contables pausadas', true);
  static readonly Unavailable = new CommandFailure('UNAVAILABLE', 'Operación temporalmente no disponible');
  static readonly Unknown = new CommandFailure('UNKNOWN', 'Resultado desconocido');
  private constructor(readonly wire: string | null, readonly label: string, readonly blocksWrites = false) {}
  static fromWire(raw: unknown): CommandFailure {
    return [this.None, this.NotVisible, this.Forbidden, this.Validation, this.Closed, this.TransitionConflict,
      this.CashConflict, this.NonTerminalSale, this.PayloadMismatch, this.LegacyDisabled, this.NotActivated, this.Paused, this.Unavailable]
      .find(item => item.wire === raw) ?? this.Unknown;
  }
}

export class AccountingCoverage {
  static readonly Complete = new AccountingCoverage('COMPLETE_FROM_OPENING', 'Cobertura desde apertura');
  static readonly LegacyIncomplete = new AccountingCoverage('LEGACY_INCOMPLETE', 'Histórico sin cobertura');
  static readonly Unknown = new AccountingCoverage('UNKNOWN', 'Cobertura desconocida');
  private constructor(readonly wire: string, readonly label: string) {}
  static fromWire(raw: unknown): AccountingCoverage {
    return [this.Complete, this.LegacyIncomplete].find(item => item.wire === raw) ?? this.Unknown;
  }
}
export interface CloseSnapshot {
  readonly declared: TicketMoney; readonly expected: TicketMoney | null; readonly difference: TicketMoney | null;
  readonly outcome: ReconciliationOutcome; readonly coverage: AccountingCoverage;
}
export interface CommandReceipt {
  readonly outcome: CommandOutcome; readonly failure: CommandFailure; readonly commandId: string | null;
  readonly cashSessionId: number | null; readonly paymentId: number | null; readonly expenseId: number | null; readonly settlementId: number | null;
  readonly closeSnapshot: CloseSnapshot | null;
}
export class AccountingCommand {
  private constructor(readonly kind: AccountingCommandKind, readonly commandId: string,
    readonly body: Readonly<Record<string, unknown>>, readonly aggregateId?: number) {}
  static fromJournal(kind: AccountingCommandKind, commandId: string, body: Readonly<Record<string, unknown>>, aggregateId?: number): AccountingCommand {
    return new AccountingCommand(kind, commandId, Object.freeze({ ...body }), aggregateId);
  }
  static create(kind: AccountingCommandKind, body: Readonly<Record<string, unknown>>, aggregateId?: number): AccountingCommand {
    const commandId = crypto.randomUUID();
    // PaymentCaptureV2Request defaults its optional reason to null; blank text is invalid.
    const reason = body['reason'];
    const captureFields = kind === AccountingCommandKind.Capture
      ? { reason: typeof reason === 'string' ? reason.trim() || null : reason ?? null }
      : {};
    return new AccountingCommand(kind, commandId, Object.freeze({ ...body, ...captureFields, commandId }), aggregateId);
  }
  accepts(receipt: CommandReceipt): boolean {
    if (receipt.outcome !== CommandOutcome.Committed) return true;
    if (this.kind === AccountingCommandKind.Close) return receipt.cashSessionId === this.aggregateId && !!receipt.closeSnapshot &&
      receipt.closeSnapshot.declared.cents === TicketMoney.fromDecimal(this.body['declaredCash'])?.cents;
    if (this.kind === AccountingCommandKind.Expense) return receipt.cashSessionId === this.body['cashSessionId'] && !!receipt.expenseId &&
      (this.body['operation'] !== ExpenseOperation.AccrueAndSettle.wire || !!receipt.settlementId);
    if (this.kind === AccountingCommandKind.Capture) return !!receipt.paymentId;
    if (this.kind === AccountingCommandKind.Reverse) return !!receipt.paymentId && receipt.paymentId !== this.aggregateId;
    return !!receipt.cashSessionId;
  }
}
