export class CashSessionStatus {
  static readonly Open = new CashSessionStatus('OPEN', 'abierta', true, false, false);
  static readonly Closed = new CashSessionStatus('CLOSED', 'cerrada', false, true, true);
  static readonly ReconciliationRequired = new CashSessionStatus(
    'RECONCILIATION_REQUIRED',
    'requiere conciliación',
    false,
    false,
    true,
  );
  static readonly Unknown = new CashSessionStatus('unknown', 'estado desconocido', false, false, false);

  private constructor(
    readonly wire: string,
    readonly label: string,
    readonly isOpen: boolean,
    readonly isClosed: boolean,
    readonly canStartNewSession: boolean,
  ) {}

  static fromWire(raw: unknown): CashSessionStatus {
    switch (raw) {
      case 'OPEN': return CashSessionStatus.Open;
      case 'CLOSED': return CashSessionStatus.Closed;
      case 'RECONCILIATION_REQUIRED': return CashSessionStatus.ReconciliationRequired;
      default: return CashSessionStatus.Unknown;
    }
  }
}

export class SaleStatus {
  static readonly PendingReservation = new SaleStatus('PENDING_RESERVATION', 'reserva pendiente');
  static readonly Reserved = new SaleStatus('RESERVED', 'reservada');
  static readonly PaymentCaptured = new SaleStatus('PAYMENT_CAPTURED', 'pago capturado');
  static readonly CommitPending = new SaleStatus('COMMIT_PENDING', 'confirmación pendiente');
  static readonly Committed = new SaleStatus('COMMITTED', 'confirmada');
  static readonly ReleasePending = new SaleStatus('RELEASE_PENDING', 'liberación pendiente');
  static readonly Released = new SaleStatus('RELEASED', 'liberada');
  static readonly ReconciliationRequired = new SaleStatus('RECONCILIATION_REQUIRED', 'requiere conciliación');
  static readonly Unknown = new SaleStatus('unknown', 'estado desconocido');

  private constructor(readonly wire: string, readonly label: string) {}

  static fromWire(raw: unknown): SaleStatus {
    switch (raw) {
      case 'PENDING_RESERVATION': return SaleStatus.PendingReservation;
      case 'RESERVED': return SaleStatus.Reserved;
      case 'PAYMENT_CAPTURED': return SaleStatus.PaymentCaptured;
      case 'COMMIT_PENDING': return SaleStatus.CommitPending;
      case 'COMMITTED': return SaleStatus.Committed;
      case 'RELEASE_PENDING': return SaleStatus.ReleasePending;
      case 'RELEASED': return SaleStatus.Released;
      case 'RECONCILIATION_REQUIRED': return SaleStatus.ReconciliationRequired;
      default: return SaleStatus.Unknown;
    }
  }
}

export class PaymentStatus {
  static readonly Pending = new PaymentStatus('PENDING', 'pendiente');
  static readonly Captured = new PaymentStatus('CAPTURED', 'capturado');
  static readonly Voided = new PaymentStatus('VOIDED', 'anulado');
  static readonly Refunded = new PaymentStatus('REFUNDED', 'reintegrado');
  static readonly Unknown = new PaymentStatus('unknown', 'estado desconocido');

  private constructor(readonly wire: string, readonly label: string) {}

  static fromWire(raw: unknown): PaymentStatus {
    switch (raw) {
      case 'PENDING': return PaymentStatus.Pending;
      case 'CAPTURED': return PaymentStatus.Captured;
      case 'VOIDED': return PaymentStatus.Voided;
      case 'REFUNDED': return PaymentStatus.Refunded;
      default: return PaymentStatus.Unknown;
    }
  }
}

export class PaymentMethod {
  static readonly Cash = new PaymentMethod('CASH', 'efectivo');
  static readonly Card = new PaymentMethod('CARD', 'tarjeta');
  static readonly Transfer = new PaymentMethod('TRANSFER', 'transferencia');
  static readonly Other = new PaymentMethod('OTHER', 'otro');
  static readonly Unknown = new PaymentMethod('unknown', 'medio desconocido');
  static readonly selectable = [PaymentMethod.Cash, PaymentMethod.Card, PaymentMethod.Transfer, PaymentMethod.Other] as const;

  private constructor(readonly wire: string, readonly label: string) {}

  static fromWire(raw: unknown): PaymentMethod {
    switch (raw) {
      case 'CASH': return PaymentMethod.Cash;
      case 'CARD': return PaymentMethod.Card;
      case 'TRANSFER': return PaymentMethod.Transfer;
      case 'OTHER': return PaymentMethod.Other;
      default: return PaymentMethod.Unknown;
    }
  }
}

export class StaffRole {
  static readonly Cashier = new StaffRole('CASHIER', 'cajero');
  static readonly Supervisor = new StaffRole('SUPERVISOR', 'supervisor');
  static readonly Owner = new StaffRole('OWNER', 'titular');
  static readonly Auditor = new StaffRole('AUDITOR', 'auditor');
  static readonly Unknown = new StaffRole('unknown', 'rol desconocido');

  private constructor(readonly wire: string, readonly label: string) {}

  static fromWire(raw: unknown): StaffRole {
    switch (raw) {
      case 'CASHIER': return StaffRole.Cashier;
      case 'SUPERVISOR': return StaffRole.Supervisor;
      case 'OWNER': return StaffRole.Owner;
      case 'AUDITOR': return StaffRole.Auditor;
      default: return StaffRole.Unknown;
    }
  }
}

export class ReportFormula {
  static readonly Contribution = new ReportFormula('CONTRIBUTION', 'contribución');
  static readonly Unknown = new ReportFormula('unknown', 'fórmula desconocida');

  private constructor(readonly wire: string, readonly label: string) {}

  static fromWire(raw: unknown): ReportFormula {
    return raw === 'CONTRIBUTION' ? ReportFormula.Contribution : ReportFormula.Unknown;
  }
}

export class ReportPeriod {
  static readonly Shift = new ReportPeriod('SHIFT', 'turno');
  static readonly Day = new ReportPeriod('DAY', 'día');
  static readonly Unknown = new ReportPeriod('unknown', 'período desconocido');

  private constructor(readonly wire: string, readonly label: string) {}

  static fromWire(raw: unknown): ReportPeriod {
    switch (raw) {
      case 'SHIFT': return ReportPeriod.Shift;
      case 'DAY': return ReportPeriod.Day;
      default: return ReportPeriod.Unknown;
    }
  }
}

export class PersistenceMode {
  static readonly Memory = new PersistenceMode('memory', 'memoria local');
  static readonly PostgreSql = new PersistenceMode('postgresql', 'PostgreSQL');
  static readonly Unknown = new PersistenceMode('unknown', 'persistencia desconocida');

  private constructor(readonly wire: string, readonly label: string) {}

  static fromWire(raw: unknown): PersistenceMode {
    switch (raw) {
      case 'memory': return PersistenceMode.Memory;
      case 'postgresql': return PersistenceMode.PostgreSql;
      default: return PersistenceMode.Unknown;
    }
  }
}

export class SaleAction {
  static readonly Reserve = new SaleAction('reserve', 'Reserva');
  static readonly Capture = new SaleAction('capture', 'Pago');
  static readonly Reverse = new SaleAction('reverse', 'Reversa');
  static readonly Commit = new SaleAction('commit', 'Venta');
  static readonly Release = new SaleAction('release', 'Reserva');
  static readonly Unknown = new SaleAction('unknown', 'Operación');

  private constructor(readonly wire: string, readonly resultSubject: string) {}

  static fromWire(raw: unknown): SaleAction {
    switch (raw) {
      case 'reserve': return SaleAction.Reserve;
      case 'capture': return SaleAction.Capture;
      case 'reverse': return SaleAction.Reverse;
      case 'commit': return SaleAction.Commit;
      case 'release': return SaleAction.Release;
      default: return SaleAction.Unknown;
    }
  }
}
