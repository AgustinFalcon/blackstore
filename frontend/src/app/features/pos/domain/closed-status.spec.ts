import { CashSessionStatus, PaymentStatus, PosPaymentMethod, ReportFormula, ReportPeriod, SaleStatus, StaffRole } from './closed-status';

describe('closed statuses', () => {
  it('maps known wire codes and keeps an unknown value inside the type', () => {
    expect(SaleStatus.fromWire('COMMITTED')).toBe(SaleStatus.Committed);
    expect(SaleStatus.fromWire('NO_SUCH').label).toBe('desconocido');
    expect(CashSessionStatus.fromWire('OPEN').open).toBeTrue();
    expect(PaymentStatus.fromWire('REFUNDED').collected).toBeFalse();
    expect(PosPaymentMethod.fromWire('CASH').cash).toBeTrue();
    expect(ReportPeriod.fromWire('DAY').label).toBe('día');
    expect(ReportPeriod.fromWire('SHIFT')).toBe(ReportPeriod.Shift);
    expect(ReportFormula.fromWire('CONTRIBUTION')).toBe(ReportFormula.Contribution);
    expect(ReportFormula.fromWire('CONTRIBUTION').label).toBe('contribución');
    expect(ReportFormula.fromWire('NO_FORMULA').label).toBe('desconocida');
    expect(StaffRole.fromWire({ code: 'OWNER' })).toBe(StaffRole.Owner);
    expect(StaffRole.fromWire('NO_ROLE')).toBe(StaffRole.Unknown);
  });
});