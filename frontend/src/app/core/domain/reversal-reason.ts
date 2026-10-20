/** Validated audit reason used before a reversal command can enter the durable journal. */
export class ReversalReason {
  static readonly minLength = 3;
  static readonly maxLength = 500;
  static readonly guidance = `Ingresá entre ${ReversalReason.minLength} y ${ReversalReason.maxLength} caracteres, sin contar espacios al inicio o al final.`;
  private constructor(readonly wire: string) {}

  static fromInput(raw: unknown): ReversalReason | null {
    if (typeof raw !== 'string') return null;
    const value = raw.trim();
    return value.length >= ReversalReason.minLength && value.length <= ReversalReason.maxLength ? new ReversalReason(value) : null;
  }
}
