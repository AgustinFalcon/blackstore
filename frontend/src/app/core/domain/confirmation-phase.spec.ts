import { ConfirmationPhase } from './confirmation-phase';

describe('ConfirmationPhase', () => {
  it('maps the closed flow and fails closed for unknown wire values', () => {
    expect(ConfirmationPhase.fromWire('IDLE')).toBe(ConfirmationPhase.Idle);
    expect(ConfirmationPhase.fromWire('REVIEWING')).toBe(ConfirmationPhase.Reviewing);
    expect(ConfirmationPhase.fromWire('future')).toBe(ConfirmationPhase.Unknown);
    expect(ConfirmationPhase.Unknown.isReviewing).toBeFalse();
  });
});
