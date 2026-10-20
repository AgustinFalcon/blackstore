export class ConfirmationPhase {
  static readonly Idle = new ConfirmationPhase('IDLE', false);
  static readonly Reviewing = new ConfirmationPhase('REVIEWING', true);
  static readonly Unknown = new ConfirmationPhase('UNKNOWN', false);

  private constructor(readonly wire: string, readonly isReviewing: boolean) {}

  static fromWire(raw: unknown): ConfirmationPhase {
    switch (raw) {
      case 'IDLE': return ConfirmationPhase.Idle;
      case 'REVIEWING': return ConfirmationPhase.Reviewing;
      default: return ConfirmationPhase.Unknown;
    }
  }
}
