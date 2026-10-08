/** Closed schema vocabulary at the acceptance database boundary. */
export class DctAuditEvent {
  static readonly Opened = new DctAuditEvent('CASH_SESSION_OPEN');
  static readonly ExpenseRecorded = new DctAuditEvent('EXPENSE_RECORD');
  static readonly Closed = new DctAuditEvent('CASH_SESSION_CLOSE');
  static readonly AuthorizationDenied = new DctAuditEvent('AUTHORIZATION_DENIED');
  static readonly Unknown = new DctAuditEvent('unknown');
  private constructor(readonly wire: string) {}
  static fromWire(raw: unknown): DctAuditEvent {
    return [this.Opened, this.ExpenseRecorded, this.Closed, this.AuthorizationDenied].find(event => event.wire === raw) ?? this.Unknown;
  }
}
