import { PaymentMethod, SaleAction, SaleStatus } from './pos-types';
import { PaymentAttempt, PaymentCoverage, TicketIdentity, TicketMoney, TicketSnapshot, TicketTransitionPolicy, TransitionKind } from './ticket-transition';

describe('TicketTransitionPolicy (T01/T11)', () => {
  const identity: TicketIdentity = { clientInstanceId: 'client', deviceId: 'device', saleId: 'sale', operationId: 'operation' };
  const money = (value: string) => TicketMoney.fromDecimal(value)!;
  const snapshot = (changes: Partial<TicketSnapshot> = {}): TicketSnapshot => ({
    identity, status: SaleStatus.Reserved, evidenceValid: true, receipt: 'receipt', reservationRef: 'reservation',
    total: money('18'), pending: money('18'), coverage: PaymentCoverage.Unpaid, hasPaymentHistory: false,
    blocked: false, retired: false, ...changes,
  });
  const attempt: PaymentAttempt = { identity, method: PaymentMethod.Cash, amount: money('10'), fee: money('0.50') };

  it('uses exact cents and rejects subcents, overflow, coercion and rounding', () => {
    for (const value of ['18.001', '0.001', '10.005', '7.995', '1000000000000', NaN, Infinity, {}, null]) {
      expect(TicketMoney.fromDecimal(value)).withContext(String(value)).toBeNull();
    }
    expect(money('18.000').decimal).toBe('18.00');
    expect(money('999999999999.99').cents).toBe(99999999999999n);
  });

  it('denies initial reserve while an attempt is active and rejects invalid planned money', () => {
    expect(TicketTransitionPolicy.beginReserve(true, true).permitsWrite).toBeFalse();
    expect(TicketTransitionPolicy.beginReserve(false, false).permitsWrite).toBeFalse();
    expect(TicketTransitionPolicy.beginReserve(false, true).permitsWrite).toBeTrue();
    const valid = { price: money('20'), discount: money('2'), amount: money('10'), fee: money('0.50'), extra: money('8'), secondMethod: PaymentMethod.Card };
    expect(TicketTransitionPolicy.reservationAmounts(valid).permitsWrite).toBeTrue();
    expect(TicketTransitionPolicy.reservationAmounts({ ...valid, extra: money('9') }).permitsWrite).toBeFalse();
    expect(TicketTransitionPolicy.reservationAmounts({ ...valid, fee: null }).permitsWrite).toBeFalse();
  });

  it('denies all mutations for missing or Unknown state/evidence/balance', () => {
    for (const value of [null, snapshot({ status: SaleStatus.Unknown }), snapshot({ evidenceValid: false }),
      snapshot({ blocked: true }), snapshot({ retired: true }), snapshot({ coverage: PaymentCoverage.InvalidUnknown }), snapshot({ pending: null })]) {
      for (const action of [SaleAction.Capture, SaleAction.Commit, SaleAction.Release, SaleAction.Reverse]) {
        expect(TicketTransitionPolicy.decide(value, action, attempt).permitsWrite).toBeFalse();
      }
    }
  });

  it('only captures known remaining money of this identity, with fee separate', () => {
    expect(TicketTransitionPolicy.decide(snapshot(), SaleAction.Capture, attempt).permitsWrite).toBeTrue();
    expect(TicketTransitionPolicy.decide(snapshot({ pending: money('8'), coverage: PaymentCoverage.Partial, hasPaymentHistory: true }),
      SaleAction.Capture, { ...attempt, amount: money('9') }).permitsWrite).toBeFalse();
    for (const invalid of [{ ...attempt, method: PaymentMethod.Unknown }, { ...attempt, amount: money('0') },
      { ...attempt, fee: money('-1') }, { ...attempt, identity: { ...identity, saleId: 'other' } }]) {
      expect(TicketTransitionPolicy.decide(snapshot(), SaleAction.Capture, invalid).permitsWrite).toBeFalse();
    }
  });

  it('commits only exact Paid coverage and releases only empty history', () => {
    expect(TicketTransitionPolicy.decide(snapshot(), SaleAction.Release).permitsWrite).toBeTrue();
    expect(TicketTransitionPolicy.decide(snapshot(), SaleAction.Commit).permitsWrite).toBeFalse();
    expect(TicketTransitionPolicy.decide(snapshot({ coverage: PaymentCoverage.Partial, pending: money('8'), hasPaymentHistory: true }), SaleAction.Commit).permitsWrite).toBeFalse();
    const paid = snapshot({ coverage: PaymentCoverage.Paid, pending: money('0'), hasPaymentHistory: true });
    expect(TicketTransitionPolicy.decide(paid, SaleAction.Commit).permitsWrite).toBeTrue();
    expect(TicketTransitionPolicy.decide(paid, SaleAction.Release).permitsWrite).toBeFalse();
    expect(TicketTransitionPolicy.decide(snapshot({ hasPaymentHistory: true }), SaleAction.Release).permitsWrite).toBeFalse();
  });

  it('separates terminal replay and read-only pending recovery from new writes', () => {
    expect(TicketTransitionPolicy.decide(snapshot({ status: SaleStatus.Committed }), SaleAction.Commit).kind).toBe(TransitionKind.TerminalReplay);
    expect(TicketTransitionPolicy.decide(snapshot({ status: SaleStatus.CommitPending }), SaleAction.Commit).kind).toBe(TransitionKind.RecoverExistingCommand);
    expect(TicketTransitionPolicy.decide(snapshot({ status: SaleStatus.CommitPending }), SaleAction.Capture, attempt).permitsWrite).toBeFalse();
    expect(TicketTransitionPolicy.decide(snapshot({ status: SaleStatus.Released }), SaleAction.Commit).permitsWrite).toBeFalse();
  });
});
