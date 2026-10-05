import {
  CashSessionStatus,
  PaymentMethod,
  PaymentStatus,
  PersistenceMode,
  ReportFormula,
  ReportPeriod,
  SaleAction,
  SaleStatus,
  StaffRole,
} from './pos-types';

describe('POS closed wire types', () => {
  it('maps every cash-session wire to its singleton and fails closed', () => {
    expect(CashSessionStatus.fromWire('OPEN')).toBe(CashSessionStatus.Open);
    expect(CashSessionStatus.fromWire('CLOSED')).toBe(CashSessionStatus.Closed);
    expect(CashSessionStatus.fromWire('RECONCILIATION_REQUIRED')).toBe(CashSessionStatus.ReconciliationRequired);
    expect(CashSessionStatus.Open.isOpen).toBeTrue();
    expect(CashSessionStatus.Unknown.isOpen).toBeFalse();
    expect(CashSessionStatus.Closed.canStartNewSession).toBeTrue();
    expect(CashSessionStatus.Open.canStartNewSession).toBeFalse();
    expect(CashSessionStatus.ReconciliationRequired.canStartNewSession).toBeTrue();
    expect(CashSessionStatus.Unknown.canStartNewSession).toBeFalse();
    expectUnknownInputs(CashSessionStatus.fromWire, CashSessionStatus.Unknown);
  });

  it('maps every sale wire to its singleton', () => {
    const cases = [
      ['PENDING_RESERVATION', SaleStatus.PendingReservation],
      ['RESERVED', SaleStatus.Reserved],
      ['PAYMENT_CAPTURED', SaleStatus.PaymentCaptured],
      ['COMMIT_PENDING', SaleStatus.CommitPending],
      ['COMMITTED', SaleStatus.Committed],
      ['RELEASE_PENDING', SaleStatus.ReleasePending],
      ['RELEASED', SaleStatus.Released],
      ['RECONCILIATION_REQUIRED', SaleStatus.ReconciliationRequired],
    ] as const;
    cases.forEach(([wire, expected]) => expect(SaleStatus.fromWire(wire)).toBe(expected));
    expectUnknownInputs(SaleStatus.fromWire, SaleStatus.Unknown);
  });

  it('maps payment statuses and methods without accepting variants', () => {
    const statuses = [
      ['PENDING', PaymentStatus.Pending],
      ['CAPTURED', PaymentStatus.Captured],
      ['VOIDED', PaymentStatus.Voided],
      ['REFUNDED', PaymentStatus.Refunded],
    ] as const;
    statuses.forEach(([wire, expected]) => expect(PaymentStatus.fromWire(wire)).toBe(expected));

    const methods = [
      ['CASH', PaymentMethod.Cash],
      ['CARD', PaymentMethod.Card],
      ['TRANSFER', PaymentMethod.Transfer],
      ['OTHER', PaymentMethod.Other],
    ] as const;
    methods.forEach(([wire, expected]) => expect(PaymentMethod.fromWire(wire)).toBe(expected));
    expect(PaymentMethod.selectable).toEqual([
      PaymentMethod.Cash,
      PaymentMethod.Card,
      PaymentMethod.Transfer,
      PaymentMethod.Other,
    ]);
    expect(PaymentMethod.selectable).not.toContain(PaymentMethod.Unknown);
    expectUnknownInputs(PaymentStatus.fromWire, PaymentStatus.Unknown);
    expectUnknownInputs(PaymentMethod.fromWire, PaymentMethod.Unknown);
  });

  it('maps roles, report metadata, persistence and flow actions exactly', () => {
    expect(StaffRole.fromWire('CASHIER')).toBe(StaffRole.Cashier);
    expect(StaffRole.fromWire('SUPERVISOR')).toBe(StaffRole.Supervisor);
    expect(StaffRole.fromWire('OWNER')).toBe(StaffRole.Owner);
    expect(StaffRole.fromWire('AUDITOR')).toBe(StaffRole.Auditor);
    expect(ReportFormula.fromWire('CONTRIBUTION')).toBe(ReportFormula.Contribution);
    expect(ReportPeriod.fromWire('SHIFT')).toBe(ReportPeriod.Shift);
    expect(ReportPeriod.fromWire('DAY')).toBe(ReportPeriod.Day);
    expect(PersistenceMode.fromWire('memory')).toBe(PersistenceMode.Memory);
    expect(PersistenceMode.fromWire('postgresql')).toBe(PersistenceMode.PostgreSql);
    expect(SaleAction.fromWire('commit')).toBe(SaleAction.Commit);
    expect(SaleAction.fromWire('release')).toBe(SaleAction.Release);
    expect(SaleAction.fromWire('reserve')).toBe(SaleAction.Reserve);
    expect(SaleAction.fromWire('capture')).toBe(SaleAction.Capture);
    expect(SaleAction.fromWire('reverse')).toBe(SaleAction.Reverse);

    expectUnknownInputs(StaffRole.fromWire, StaffRole.Unknown);
    expectUnknownInputs(ReportFormula.fromWire, ReportFormula.Unknown);
    expectUnknownInputs(ReportPeriod.fromWire, ReportPeriod.Unknown);
    expectUnknownInputs(PersistenceMode.fromWire, PersistenceMode.Unknown);
    expectUnknownInputs(SaleAction.fromWire, SaleAction.Unknown);
  });
});

function expectUnknownInputs<T>(fromWire: (raw: unknown) => T, unknown: T): void {
  [null, undefined, '', ' open ', 'open', 'UNKNOWN_WIRE', 1, {}].forEach((raw) => {
    expect(fromWire(raw)).withContext(`raw=${String(raw)}`).toBe(unknown);
  });
}
