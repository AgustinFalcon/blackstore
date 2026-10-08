export class AccountingLifecycleState {
  static readonly PreActivation = new AccountingLifecycleState('PRE_ACTIVATION', 'Contabilidad aún no activada');
  static readonly Active = new AccountingLifecycleState('ACTIVE', 'Contabilidad activa');
  static readonly Paused = new AccountingLifecycleState('PAUSED', 'Contabilidad pausada');
  static readonly Unknown = new AccountingLifecycleState('UNKNOWN', 'Contabilidad no comprobada');
  private constructor(readonly wire: string, readonly label: string) {}
  static fromWire(raw: unknown): AccountingLifecycleState { return [this.PreActivation, this.Active, this.Paused].find(value => value.wire === raw) ?? this.Unknown; }
  get admitsWrites(): boolean { return this === AccountingLifecycleState.Active; }
}
