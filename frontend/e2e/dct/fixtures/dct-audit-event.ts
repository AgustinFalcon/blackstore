/** Closed schema vocabulary at the acceptance database boundary. */
export class DctAuditEvent {
  static readonly Opened = new DctAuditEvent('CASH_SESSION_OPENED');
  static readonly ExpenseRecorded = new DctAuditEvent('EXPENSE_RECORDED');
  static readonly Closed = new DctAuditEvent('CASH_SESSION_CLOSED');
  static readonly AuthorizationDenied = new DctAuditEvent('AUTHORIZATION_DENIED');
  static readonly Unknown = new DctAuditEvent('unknown');
  private constructor(readonly wire: string) {}
  static fromWire(raw: unknown): DctAuditEvent {
    return [this.Opened, this.ExpenseRecorded, this.Closed, this.AuthorizationDenied].find(event => event.wire === raw) ?? this.Unknown;
  }
}
