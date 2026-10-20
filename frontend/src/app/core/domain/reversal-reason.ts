/** Validated audit reason used before a reversal command can enter the durable journal. */
export class ReversalReason {
  private constructor(readonly wire: string) {}

  static fromInput(raw: unknown): ReversalReason | null {
    if (typeof raw !== 'string') return null;
    const value = raw.trim();
    return value.length >= 3 && value.length <= 500 ? new ReversalReason(value) : null;
  }
}
