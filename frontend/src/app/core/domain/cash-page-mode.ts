/** Closed presentation mode for the operational cash routes. */
export class CashPageMode {
  static readonly Workspace = new CashPageMode('WORKSPACE', 'Caja', true, true);
  static readonly Close = new CashPageMode('CLOSE', 'Cierre de caja', false, false);
  static readonly Unknown = new CashPageMode('UNKNOWN', 'Caja no disponible', false, false);
  static readonly values = [CashPageMode.Workspace, CashPageMode.Close] as const;

  private constructor(
    readonly wire: string,
    readonly title: string,
    readonly allowsOpen: boolean,
    readonly allowsExpense: boolean,
  ) {}

  static fromWire(raw: unknown): CashPageMode {
    return CashPageMode.values.find(value => value.wire === raw) ?? CashPageMode.Unknown;
  }
}

export class CashCloseAvailability {
  static readonly Ready = new CashCloseAvailability('READY', 'Caja lista para revisar');
  static readonly NoSessions = new CashCloseAvailability('NO_SESSIONS', 'No hay cajas visibles disponibles para cerrar.');
  static readonly SelectionRequired = new CashCloseAvailability('SELECTION_REQUIRED', 'Elegí una caja visible para iniciar el cierre.');
  static readonly InvalidSelection = new CashCloseAvailability('INVALID_SELECTION', 'La selección de caja no es válida. Elegí una caja visible.');
  static readonly AmbiguousSelection = new CashCloseAvailability('AMBIGUOUS_SELECTION', 'La selección coincide con varias cajas. Elegí una caja visible inequívoca.');
  static readonly Unknown = new CashCloseAvailability('UNKNOWN', 'No se pudo comprobar la disponibilidad de cierre.');
  static readonly values = [CashCloseAvailability.Ready, CashCloseAvailability.NoSessions,
    CashCloseAvailability.SelectionRequired, CashCloseAvailability.InvalidSelection,
    CashCloseAvailability.AmbiguousSelection] as const;

  private constructor(readonly wire: string, readonly label: string) {}

  static fromWire(raw: unknown): CashCloseAvailability {
    return CashCloseAvailability.values.find(value => value.wire === raw) ?? CashCloseAvailability.Unknown;
  }
}
