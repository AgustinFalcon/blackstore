import { CashCloseAvailability, CashPageMode } from './cash-page-mode';

describe('cash route presentation types', () => {
  it('maps supported modes and fails closed', () => {
    expect(CashPageMode.fromWire('WORKSPACE')).toBe(CashPageMode.Workspace);
    expect(CashPageMode.fromWire('CLOSE')).toBe(CashPageMode.Close);
    expect(CashPageMode.fromWire('future-mode')).toBe(CashPageMode.Unknown);
    expect(CashPageMode.Unknown.allowsOpen).toBeFalse();
    expect(CashPageMode.Unknown.allowsExpense).toBeFalse();
  });

  it('maps close availability without leaking unknown wire values', () => {
    expect(CashCloseAvailability.fromWire('READY')).toBe(CashCloseAvailability.Ready);
    expect(CashCloseAvailability.fromWire('future')).toBe(CashCloseAvailability.Unknown);
  });
});
