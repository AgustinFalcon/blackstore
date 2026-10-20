import { ReversalReason } from './reversal-reason';

describe('ReversalReason', () => {
  it('normalizes a reason accepted by the backend contract', () => {
    expect(ReversalReason.fromInput('  devolución por error  ')?.wire).toBe('devolución por error');
  });

  it('rejects values outside the backend 3..500 character contract', () => {
    for (const value of [null, '', ' x ', 'ok', 'x'.repeat(501)]) {
      expect(ReversalReason.fromInput(value)).toBeNull();
    }
  });
});
