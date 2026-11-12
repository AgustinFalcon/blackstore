import { RecordedInstant } from './recorded-instant';

describe('RecordedInstant', () => {
  it('translates a valid wire once and formats it without exposing the raw value', () => {
    const value = RecordedInstant.fromWire('2026-11-12T14:30:00Z');
    expect(value.known).toBeTrue();
    expect(value.format()).not.toBe('No disponible');
    expect(value.format()).not.toContain('2026-11-12T14:30:00Z');
  });

  it('fails closed for absent, malformed and non-trimmed wires', () => {
    for (const raw of [null, undefined, '', ' 2026-11-12T14:30:00Z', '2026-02-30T14:30:00Z', '2026-11-12T14:30:00+00:00', 'not-a-date', 42]) {
      const value = RecordedInstant.fromWire(raw);
      expect(value).toBe(RecordedInstant.Unknown);
      expect(value.known).toBeFalse();
      expect(value.format()).toBe('No disponible');
    }
  });
});
