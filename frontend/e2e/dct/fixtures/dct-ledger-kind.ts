/** Closed schema vocabulary at the PostgreSQL acceptance boundary. */
export class DctLedgerKind {
  static readonly Opening = new DctLedgerKind('OPENING');
  static readonly Accrual = new DctLedgerKind('EXPENSE_ACCRUAL');
  static readonly Paid = new DctLedgerKind('EXPENSE_PAID');
  static readonly Unknown = new DctLedgerKind('UNKNOWN');
  private constructor(readonly wire: string) {}
  static fromWire(raw: unknown): DctLedgerKind {
    return [this.Opening, this.Accrual, this.Paid].find(kind => kind.wire === raw) ?? this.Unknown;
  }
}
