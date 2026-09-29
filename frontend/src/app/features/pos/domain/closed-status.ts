abstract class ClosedStatus {
  protected constructor(
    readonly code: string,
    readonly label: string,
  ) {}
}

function wireCode(raw: unknown): unknown {
  if (typeof raw === 'string') return raw;
  if (raw && typeof raw === 'object' && 'code' in raw) return (raw as { code: unknown }).code;
  return raw;
}

export class StaffRole extends ClosedStatus {
  private constructor(code: string, label: string, readonly capabilities: RoleCapabilities) {
    super(code, label);
  }

  static readonly Cashier = new StaffRole('CASHIER', 'Cajero', { cash: true, sell: true, reports: false, close: false });
  static readonly Supervisor = new StaffRole('SUPERVISOR', 'Encargado', { cash: true, sell: true, reports: true, close: true });
  static readonly Owner = new StaffRole('OWNER', 'Titular', { cash: true, sell: true, reports: true, close: true });
  static readonly Auditor = new StaffRole('AUDITOR', 'Auditor', { cash: false, sell: false, reports: true, close: false });
  static readonly Unknown = new StaffRole('UNKNOWN', 'Desconocido', { cash: false, sell: false, reports: false, close: false });
  static readonly known = [StaffRole.Cashier, StaffRole.Supervisor, StaffRole.Owner, StaffRole.Auditor] as const;

  static fromWire(raw: unknown): StaffRole {
    const code = wireCode(raw);
    return StaffRole.known.find((role) => role.code === code) ?? StaffRole.Unknown;
  }
}

export interface RoleCapabilities {
  readonly cash: boolean;
  readonly sell: boolean;
  readonly reports: boolean;
  readonly close: boolean;
}

export function roleCapabilities(role: StaffRole | null): RoleCapabilities {
  return (role ?? StaffRole.Unknown).capabilities;
}

export class CashSessionStatus extends ClosedStatus {
  private constructor(code: string, label: string) {
    super(code, label);
  }

  static readonly Open = new CashSessionStatus('OPEN', 'abierta');
  static readonly Closed = new CashSessionStatus('CLOSED', 'cerrada');
  static readonly Reconciliation = new CashSessionStatus('RECONCILIATION_REQUIRED', 'requiere arqueo');
  static readonly Unknown = new CashSessionStatus('UNKNOWN', 'desconocido');
  static readonly known = [CashSessionStatus.Open, CashSessionStatus.Closed, CashSessionStatus.Reconciliation] as const;

  static fromWire(raw: unknown): CashSessionStatus {
    const code = wireCode(raw);
    return CashSessionStatus.known.find((status) => status.code === code) ?? CashSessionStatus.Unknown;
  }

  get open(): boolean {
    return this === CashSessionStatus.Open;
  }

  get closed(): boolean {
    return this === CashSessionStatus.Closed;
  }
}

export class SaleStatus extends ClosedStatus {
  private constructor(code: string, label: string) {
    super(code, label);
  }

  static readonly PendingReservation = new SaleStatus('PENDING_RESERVATION', 'reserva pendiente');
  static readonly Reserved = new SaleStatus('RESERVED', 'reservada');
  static readonly PaymentCaptured = new SaleStatus('PAYMENT_CAPTURED', 'cobrada');
  static readonly CommitPending = new SaleStatus('COMMIT_PENDING', 'confirmación pendiente');
  static readonly Committed = new SaleStatus('COMMITTED', 'confirmada');
  static readonly ReleasePending = new SaleStatus('RELEASE_PENDING', 'liberación pendiente');
  static readonly Released = new SaleStatus('RELEASED', 'liberada');
  static readonly Reconciliation = new SaleStatus('RECONCILIATION_REQUIRED', 'requiere conciliación');
  static readonly Unknown = new SaleStatus('UNKNOWN', 'desconocido');
  static readonly known = [
    SaleStatus.PendingReservation,
    SaleStatus.Reserved,
    SaleStatus.PaymentCaptured,
    SaleStatus.CommitPending,
    SaleStatus.Committed,
    SaleStatus.ReleasePending,
    SaleStatus.Released,
    SaleStatus.Reconciliation,
  ] as const;

  static fromWire(raw: unknown): SaleStatus {
    const code = wireCode(raw);
    return SaleStatus.known.find((status) => status.code === code) ?? SaleStatus.Unknown;
  }

  get canFinish(): boolean {
    return this === SaleStatus.Reserved || this === SaleStatus.PaymentCaptured;
  }
}

export class PaymentStatus extends ClosedStatus {
  private constructor(code: string, label: string) {
    super(code, label);
  }

  static readonly Pending = new PaymentStatus('PENDING', 'pendiente');
  static readonly Captured = new PaymentStatus('CAPTURED', 'cobrado');
  static readonly Voided = new PaymentStatus('VOIDED', 'anulado');
  static readonly Refunded = new PaymentStatus('REFUNDED', 'reversado');
  static readonly Unknown = new PaymentStatus('UNKNOWN', 'desconocido');
  static readonly known = [PaymentStatus.Pending, PaymentStatus.Captured, PaymentStatus.Voided, PaymentStatus.Refunded] as const;

  static fromWire(raw: unknown): PaymentStatus {
    const code = wireCode(raw);
    return PaymentStatus.known.find((status) => status.code === code) ?? PaymentStatus.Unknown;
  }

  get collected(): boolean {
    return this === PaymentStatus.Captured || this === PaymentStatus.Pending;
  }
}

export class ReportPeriod extends ClosedStatus {
  private constructor(code: string, label: string) {
    super(code, label);
  }

  static readonly Shift = new ReportPeriod('SHIFT', 'turno');
  static readonly Day = new ReportPeriod('DAY', 'día');
  static readonly Unknown = new ReportPeriod('UNKNOWN', 'período desconocido');
  static readonly known = [ReportPeriod.Shift, ReportPeriod.Day] as const;

  static fromWire(raw: unknown): ReportPeriod {
    const code = wireCode(raw);
    return ReportPeriod.known.find((period) => period.code === code) ?? ReportPeriod.Unknown;
  }
}

export class ReportFormula extends ClosedStatus {
  private constructor(code: string, label: string) {
    super(code, label);
  }

  static readonly Contribution = new ReportFormula('CONTRIBUTION', 'contribución');
  static readonly Unknown = new ReportFormula('UNKNOWN', 'desconocida');
  static readonly known = [ReportFormula.Contribution] as const;

  static fromWire(raw: unknown): ReportFormula {
    const code = wireCode(raw);
    return ReportFormula.known.find((formula) => formula.code === code) ?? ReportFormula.Unknown;
  }
}

export class PosPaymentMethod extends ClosedStatus {
  private constructor(code: string, label: string) {
    super(code, label);
  }

  static readonly Cash = new PosPaymentMethod('CASH', 'efectivo');
  static readonly Card = new PosPaymentMethod('CARD', 'tarjeta');
  static readonly Transfer = new PosPaymentMethod('TRANSFER', 'transferencia');
  static readonly Other = new PosPaymentMethod('OTHER', 'otro');
  static readonly Unknown = new PosPaymentMethod('UNKNOWN', 'desconocido');
  static readonly known = [PosPaymentMethod.Cash, PosPaymentMethod.Card, PosPaymentMethod.Transfer, PosPaymentMethod.Other] as const;

  static fromWire(raw: unknown): PosPaymentMethod {
    const code = wireCode(raw);
    return PosPaymentMethod.known.find((method) => method.code === code) ?? PosPaymentMethod.Unknown;
  }

  get cash(): boolean {
    return this === PosPaymentMethod.Cash;
  }
}
